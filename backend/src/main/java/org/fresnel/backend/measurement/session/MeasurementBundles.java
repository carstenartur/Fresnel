package org.fresnel.backend.measurement.session;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.fresnel.backend.measurement.session.MeasurementSessions.*;

/** A portable capture retains the exact inputs, their semantic roles, parameters and optional result. */
@Service
class MeasurementBundles {
    static final String TYPE = "application/vnd.carstenartur.fresnel.experiment+zip";
    private static final int MANIFEST_MAX = 96 << 10;
    private final MeasurementSessions sessions;
    private final ExperimentDocuments documents;
    MeasurementBundles(MeasurementSessions sessions, ExperimentDocuments documents) {
        this.sessions = sessions; this.documents = documents;
    }
    record Manifest(int version, Plan plan, Map<String,Asset> assets, String jobSha256, String reportSha256, CaptureEvidence capture) {}

    void export(String owner, UUID id, OutputStream output) throws IOException {
        var lock = sessions.lock(owner); lock.lock();
        try {
            View view = sessions.get(owner, id);
            try (var zip = new ZipOutputStream(output)) {
                byte[] job = documents.job(view.plan());
                var capture = CaptureEvidence.read(sessions.dir(owner,id),sessions.json);
                if (capture != null) capture.verify(view.plan(), view.assets());
                byte[] report = documents.report(view.plan(), view.assets(), job != null, capture);
                byte[] manifest = sessions.json.writeValueAsBytes(new Manifest(1, view.plan(), new TreeMap<>(view.assets()),
                        job == null ? null : hash(job), hash(report), capture));
                add(zip, "manifest.json", new ByteArrayInputStream(manifest));
                add(zip, "reports/summary.md", new ByteArrayInputStream(report));
                if (job != null) add(zip, "job.fresnel", new ByteArrayInputStream(job));
                Set<String> names = new HashSet<>();
                for (var item : new TreeMap<>(view.assets()).entrySet()) {
                    String path = entryName(item.getKey(), item.getValue());
                    if (names.add(path)) try (var source = Files.newInputStream(sessions.source(owner, id, item.getKey()).path())) {
                        add(zip, path, source);
                    }
                }
            }
        } finally { lock.unlock(); }
    }
    private static void add(ZipOutputStream zip, String path, InputStream data) throws IOException {
        var entry = new ZipEntry(path);
        entry.setTimeLocal(java.time.LocalDateTime.of(2000,1,1,0,0));
        zip.putNextEntry(entry);
        data.transferTo(zip);
        zip.closeEntry();
    }

    View read(String owner, InputStream input) throws IOException {
        Path stage = Files.createTempFile("fresnel-experiment-", ".zip");
        try {
            try (var out = Files.newOutputStream(stage)) { copy(input, out, SESSION_BYTES + (2L << 20)); }
            checkDirectory(stage);
            try (var zip = ZipFile.builder().setPath(stage).get()) { return importEntries(owner, zip); }
            catch (java.util.zip.ZipException e) { throw bad("Invalid experiment ZIP"); }
        } finally { Files.deleteIfExists(stage); }
    }

    // Writers produce a single-volume classic ZIP. Bound the central directory before opening it.
    private static void checkDirectory(Path stage) throws IOException {
        try (var file = new RandomAccessFile(stage.toFile(), "r")) {
            if (file.length() < 22) throw bad("Incomplete ZIP");
            file.seek(file.length() - 22);
            byte[] end = new byte[22]; file.readFully(end);
            var header = java.nio.ByteBuffer.wrap(end).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int entries = Short.toUnsignedInt(header.getShort(10));
            long directorySize = Integer.toUnsignedLong(header.getInt(12));
            long directoryOffset = Integer.toUnsignedLong(header.getInt(16));
            if (header.getInt(0) != 0x06054b50 || header.getInt(4) != 0 || header.getShort(20) != 0
                    || entries != Short.toUnsignedInt(header.getShort(8)) || entries == 0 || entries > 24
                    || directorySize > 8192 || directoryOffset + directorySize != file.length() - 22) throw bad("Unsupported ZIP layout");
        }
    }

    private View importEntries(String owner, ZipFile zip) throws IOException {
        var entries = new HashMap<String,ZipArchiveEntry>();
        long expanded = 0;
        for (var iterator = zip.getEntries(); iterator.hasMoreElements();) {
            var entry = iterator.nextElement();
            if (entries.size() >= 24 || entry.isDirectory() || entry.isUnixSymlink()
                    || !zip.canReadEntryData(entry) || entry.getSize() < 0 || entry.getSize() > RESULT_BYTES
                    || (entry.getMethod() != ZipEntry.DEFLATED && entry.getMethod() != ZipEntry.STORED)
                    || entries.putIfAbsent(entry.getName(), entry) != null) throw bad("Invalid experiment ZIP entry");
            expanded += entry.getSize();
            if (expanded > SESSION_BYTES + 3L * MANIFEST_MAX) throw bad("Experiment exceeds size limit");
        }
        var metadata = entries.get("manifest.json");
        if (metadata == null || metadata.getSize() > MANIFEST_MAX) throw bad("Missing or oversized manifest");
        Manifest m;
        try (var in = zip.getInputStream(metadata)) {
            var bytes = new ByteArrayOutputStream();
            copy(in, bytes, MANIFEST_MAX);
            m = sessions.json.readValue(bytes.toByteArray(), Manifest.class);
        } catch (tools.jackson.core.JacksonException e) { throw bad("Invalid experiment manifest"); }
        if (m == null || m.version != 1 || m.assets == null) throw bad("Unsupported experiment manifest");
        sessions.validate(m.plan);
        if (m.capture != null) m.capture.verify(m.plan, m.assets);
        Set<String> expected = new HashSet<>(Set.of("manifest.json"));
        if (m.reportSha256 != null) {
            checkedDocument(zip, entries, "reports/summary.md", m.reportSha256);
            expected.add("reports/summary.md");
        }
        if (m.jobSha256 != null) {
            byte[] job = checkedDocument(zip, entries, "job.fresnel", m.jobSha256);
            documents.checkJob(job, m.plan);
            expected.add("job.fresnel");
        }
        for (var item : m.assets.entrySet()) {
            Asset a = item.getValue();
            if ((!m.plan.steps().contains(item.getKey()) && !"result".equals(item.getKey())) || a == null ||
                    a.sha256() == null || !a.sha256().matches("[0-9a-f]{64}")) throw bad("Invalid asset declaration");
            String name = entryName(item.getKey(), a);
            expected.add(name);
            if (!entries.containsKey(name) || entries.get(name).getSize() != a.byteLength()) throw bad("Asset size mismatch");
        }
        if (!expected.equals(entries.keySet())) throw bad("Unexpected file in experiment");
        // Hold the owner lock until all inputs pass normal upload validation, including their digest.
        var lock = sessions.lock(owner); lock.lock();
        try {
            if (sessions.list(owner).size() >= MAX_SESSIONS) throw conflict("Delete an older measurement before importing another");
            Path ownerDirectory = sessions.ownerDir(owner);
            Files.createDirectories(ownerDirectory);
            Path stagingDirectory = Files.createTempDirectory(ownerDirectory, ".import-");
            try {
                // A restart must never expose a half-imported experiment as a saved session.
                var staging = new MeasurementSessions(sessions.json, stagingDirectory.toString());
                View v = staging.create(owner, m.plan);
                List<String> order = new ArrayList<>(m.plan.steps());
                order.add("result");
                for (String step : order) {
                    Asset a = m.assets.get(step);
                    if (a == null) continue;
                    try (var in = zip.getInputStream(entries.get(entryName(step, a)))) {
                        v = staging.put(owner, v.id(), step, a.mediaType(), a.sha256(), in);
                    }
                    if (!v.assets().get(step).equals(a)) throw bad("Asset metadata mismatch");
                }
                if (m.capture != null) Files.write(staging.dir(owner,v.id()).resolve(CaptureEvidence.FILE),
                        sessions.json.writeValueAsBytes(m.capture), StandardOpenOption.CREATE_NEW);
                Files.move(staging.dir(owner, v.id()), sessions.dir(owner, v.id()), StandardCopyOption.ATOMIC_MOVE);
                return v;
            } finally { MeasurementSessions.remove(stagingDirectory); }
        } finally { lock.unlock(); }
    }

    private static byte[] checkedDocument(ZipFile zip, Map<String,ZipArchiveEntry> entries, String name, String digest) throws IOException {
        var entry = entries.get(name);
        if (entry == null || entry.getSize() > MANIFEST_MAX) throw bad("Missing or oversized experiment document");
        try (var stream = zip.getInputStream(entry)) {
            var bytes = new ByteArrayOutputStream();
            copy(stream, bytes, MANIFEST_MAX);
            byte[] data = bytes.toByteArray();
            if (!hash(data).equals(digest)) throw bad("Experiment document checksum mismatch");
            return data;
        }
    }

    private static String entryName(String step, Asset a) {
        if ("result".equals(step) && "application/json".equals(a.mediaType())) return "results/result.json";
        if ("image/png".equals(a.mediaType())) return "assets/" + a.sha256() + ".png";
        if ("image/jpeg".equals(a.mediaType())) return "assets/" + a.sha256() + ".jpg";
        throw bad("Unsupported archive media type");
    }
}

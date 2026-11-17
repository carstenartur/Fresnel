package org.fresnel.backend.measurement.session;

import org.fresnel.backend.desktop.DesktopDataDirectory;
import org.fresnel.measurement.CaptureSource;
import org.fresnel.measurement.UploadCaptureSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.*;

@Service
public class MeasurementSessions implements UploadCaptureSource<MeasurementSessions.Plan, MeasurementSessions.View> {

    @Override public CaptureSource.Info source() {
        return new CaptureSource.Info("manual-upload", "Upload camera files",
                CaptureSource.Kind.FILE_UPLOAD);
    }

    @Override public View create(String owner, UUID id, Plan plan) throws IOException {
        return create(owner, id, plan, "manual-upload");
    }
    static final long IMAGE_BYTES = 20L << 20, RESULT_BYTES = 24L << 20, SESSION_BYTES = 160L << 20;
    static final long VIDEO_BYTES = 64L << 20;
    static final int MAX_BOS_FRAMES = 24;
    static final int PLAN_BYTES = 64 << 10, MAX_SESSIONS = 32;
    private final Path root;
    final ObjectMapper json;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    MeasurementSessions(ObjectMapper json, @Value("${fresnel.measurements.directory:}") String directory) {
        this.json = json;
        root = (directory.isBlank() ? DesktopDataDirectory.resolve().resolve("measurements") : Path.of(directory)).toAbsolutePath();
        Arrays.setAll(locks, i -> new ReentrantLock());
    }

    public record Plan(String pluginId, JsonNode parameters, List<String> steps) {}
    public record Asset(String sha256, long byteLength, String mediaType, int width, int height) {}
    public record View(UUID id, Instant created, String pluginId, JsonNode parameters, List<String> steps,
                       Map<String,Asset> assets, String state, String captureProvider) {
        public View { if (captureProvider == null) captureProvider = "manual-upload"; }
        Plan plan() { return new Plan(pluginId, parameters, steps); }
    }
    record Source(Asset asset, Path path) {}

    // Sessions of one owner share a lock so deletion/quotas and bundle snapshots are atomic within the service.
    ReentrantLock lock(String owner) { return locks[Math.floorMod(owner.hashCode(), locks.length)]; }
    Path ownerDir(String owner) { return root.resolve(hash(owner.getBytes(StandardCharsets.UTF_8))); }
    Path dir(String owner, UUID id) { return ownerDir(owner).resolve(id.toString()); }

    void validate(Plan plan) {
        if (plan == null || plan.pluginId == null || plan.steps == null || plan.steps.size() > MAX_BOS_FRAMES + 1 || plan.parameters == null
                || !plan.parameters.isObject() || json.writeValueAsBytes(plan.parameters).length > PLAN_BYTES)
            throw bad("Invalid measurement plan or parameter size");
        boolean ok = switch (plan.pluginId) {
            case "background-oriented-schlieren" -> validBosSteps(plan.steps);
            case "display-photometric-stereo" -> {
                var lights = new ArrayList<>(plan.steps);
                lights.remove("dark");
                lights.remove("flat");
                boolean valid = Set.of(4,8,12,16).contains(lights.size());
                for (int i = 0; valid && i < lights.size(); i++)
                    valid = String.format(Locale.ROOT, "light-%02d", i + 1).equals(lights.get(i));
                yield valid;
            }
            default -> false;
        };
        if (!ok) throw bad("Unsupported plugin or capture steps");
    }

    private static boolean validBosSteps(List<String> steps) {
        if (steps.equals(List.of("reference", "disturbed")) || steps.equals(List.of("video"))
                || steps.equals(List.of("reference","video"))) return true;
        int start = !steps.isEmpty() && "reference".equals(steps.getFirst()) ? 1 : 0;
        int frames = steps.size() - start;
        if (frames < 2 || frames > MAX_BOS_FRAMES) return false;
        for (int i=0; i<frames; i++) {
            if (!String.format(Locale.ROOT, "frame-%02d", i+1).equals(steps.get(start+i))) return false;
        }
        return true;
    }

    List<View> list(String owner) throws IOException {
        Path parent = ownerDir(owner);
        if (!Files.isDirectory(parent)) return List.of();
        var lock = lock(owner); lock.lock();
        try (var paths = Files.list(parent)) {
            var result = new ArrayList<View>();
            for (Path p : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}")).toList()) {
                if (Files.isRegularFile(p.resolve("session.json"))) result.add(load(p));
            }
            result.sort(Comparator.comparing(View::created).reversed());
            return result;
        } finally { lock.unlock(); }
    }

    View create(String owner, Plan plan) throws IOException {
        return create(owner, UUID.randomUUID(), plan, "manual-upload");
    }

    View create(String owner, UUID id, Plan plan, String provider) throws IOException {
        validate(plan);
        var lock = lock(owner); lock.lock();
        try {
            if (Files.isRegularFile(dir(owner,id).resolve("session.json"))) {
                var previous = get(owner,id);
                if (previous.plan().equals(plan) && previous.captureProvider().equals(provider)) return previous;
                throw conflict("Session already exists with different capture settings");
            }
            if (list(owner).size() >= MAX_SESSIONS) throw new ResponseStatusException(HttpStatus.CONFLICT, "Delete an older measurement before creating another");
            var view = new View(id, Instant.now(), plan.pluginId, plan.parameters, List.copyOf(plan.steps), Map.of(), "READY_TO_CAPTURE", provider);
            Path directory = dir(owner, view.id);
            Files.createDirectories(directory);
            try { save(directory, view); }
            catch (IOException | RuntimeException error) { remove(directory); throw error; }
            return view;
        } finally { lock.unlock(); }
    }

    record Derivation(Plan plan, Map<String,String> sourceHashes) {}

    View derive(String owner, UUID id, UUID sourceId, Derivation request) throws IOException {
        if (id.equals(sourceId) || request == null || request.sourceHashes() == null)
            throw bad("Choose a distinct destination and the verified source files");
        Plan plan = request.plan();
        validate(plan);
        var guard = lock(owner);
        guard.lock();
        try {
            View source = get(owner, sourceId);
            if (!PhotometryCapture.PLUGIN.equals(plan.pluginId()) || !source.pluginId().equals(plan.pluginId())
                    || !plan.steps().equals(source.steps()) || !sameGeometry(plan.parameters(), source.parameters())
                    || !request.sourceHashes().keySet().equals(Set.copyOf(source.steps())))
                throw conflict("Capture geometry or step mapping has changed");
            var assets = new TreeMap<String,Asset>();
            for (String step : source.steps()) {
                Asset asset = source.assets().get(step);
                if (asset == null || !asset.sha256().equals(request.sourceHashes().get(step)))
                    throw conflict("Source checksum differs from the acquisition");
                assets.put(step, asset);
            }
            Path target = dir(owner, id);
            if (Files.exists(target)) {
                var previous = get(owner, id);
                Path origin = target.resolve("derived-from");
                if (previous.plan().equals(plan) && Files.isRegularFile(origin)
                        && sourceId.toString().equals(Files.readString(origin))) return previous;
                throw conflict("Destination already belongs to another experiment");
            }
            if (list(owner).size() >= MAX_SESSIONS) throw conflict("Delete an older measurement before creating another");
            var evidence = CaptureEvidence.read(dir(owner, sourceId), json);
            if (evidence != null) evidence.verify(plan, assets);
            Path stage = ownerDir(owner).resolve(".deriving-" + id);
            remove(stage);
            Files.createDirectory(stage);
            try {
                for (Asset asset : new HashSet<>(assets.values())) {
                    var checksum = digest();
                    try (var in = Files.newInputStream(dir(owner, sourceId).resolve(asset.sha256()));
                         var out = new DigestOutputStream(Files.newOutputStream(stage.resolve(asset.sha256())), checksum)) {
                        if (copy(in, out, asset.byteLength()) != asset.byteLength()
                                || !asset.sha256().equals(HexFormat.of().formatHex(checksum.digest())))
                            throw conflict("Stored acquisition failed checksum verification");
                    }
                }
                if (evidence != null) Files.write(stage.resolve(CaptureEvidence.FILE), json.writeValueAsBytes(evidence));
                Files.writeString(stage.resolve("derived-from"), sourceId.toString());
                var view = new View(id, Instant.now(), plan.pluginId(), plan.parameters(), source.steps(),
                        assets, "READY_TO_ANALYZE", "manual-upload");
                save(stage, view);
                Files.move(stage, target, ATOMIC_MOVE);
                return view;
            } finally { remove(stage); }
        } finally { guard.unlock(); }
    }

    private boolean sameGeometry(JsonNode first, JsonNode second) {
        try {
            var type = org.fresnel.measurement.photometry.DisplayGeometry.class;
            return json.treeToValue(first.has("geometry") ? first.get("geometry") : first, type)
                    .equals(json.treeToValue(second.has("geometry") ? second.get("geometry") : second, type));
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) { throw bad("Invalid acquisition geometry"); }
    }

    View get(String owner, UUID id) throws IOException { return load(dir(owner, id)); }
    private View load(Path directory) throws IOException {
        try { return json.readValue(Files.readAllBytes(directory.resolve("session.json")), View.class); }
        catch (NoSuchFileException e) { throw missing(); }
    }

    Source source(String owner, UUID id, String step) throws IOException {
        var asset = get(owner, id).assets.get(step);
        if (asset == null) throw missing();
        return new Source(asset, dir(owner, id).resolve(asset.sha256));
    }

    @Override public View put(String owner, UUID id, String step, String type, String expected, InputStream input) throws IOException {
        return put(owner, id, step, type, expected, input, "manual-upload");
    }

    View put(String owner, UUID id, String step, String type, String expected, InputStream input, String provider) throws IOException {
        var lock = lock(owner); lock.lock();
        try {
            View v = get(owner, id);
            boolean result = "result".equals(step);
            if (!result && !v.captureProvider.equals(provider)) throw conflict("Source belongs to another capture provider");
            if (!v.steps.contains(step) && !result) throw bad("Unknown capture step");
            if (result && !v.assets.keySet().containsAll(v.steps)) throw conflict("Upload every source before saving a result");
            if (expected == null || !expected.matches("[0-9a-f]{64}")) throw bad("A lowercase SHA-256 is required");
            boolean video = "video".equals(step);
            if (result ? !"application/json".equals(type) : video ? !"video/mp4".equals(type) : !Set.of("image/png", "image/jpeg").contains(type))
                throw bad("Unsupported media type");
            Path directory = dir(owner, id), stage = Files.createTempFile(directory, ".upload-", ".part");
            try {
                var digest = digest();
                long size;
                try (var out = new DigestOutputStream(Files.newOutputStream(stage), digest)) {
                    size = copy(input, out, result ? RESULT_BYTES : video ? VIDEO_BYTES : IMAGE_BYTES);
                }
                if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) throw bad("Checksum mismatch");
                var existing = v.assets.get(step);
                if (existing != null) {
                    if (existing.sha256.equals(expected) && existing.mediaType.equals(type)) return v;
                    throw conflict("The step already has a different asset");
                }
                if (v.assets.values().stream().mapToLong(Asset::byteLength).sum() + size > SESSION_BYTES)
                    throw bad("Experiment exceeds its byte limit");
                Asset asset;
                if (result) {
                    var data = json.readTree(Files.readAllBytes(stage));
                    if (data == null || !data.isObject()) throw bad("Expected a result JSON object");
                    asset = new Asset(expected, size, type, 0, 0);
                } else {
                    asset = video ? BoundedMp4.inspect(stage, expected, size) : inspectImage(stage, expected, size, type);
                    if (v.assets.values().stream().anyMatch(a -> (a.width != asset.width || a.height != asset.height) &&
                            !(v.steps.contains("video") && a.width == asset.height && a.height == asset.width)))
                        throw bad("Capture dimensions differ");
                }
                var assets = new TreeMap<>(v.assets);
                assets.put(step, asset);
                String state = result ? "COMPLETED" : assets.keySet().containsAll(v.steps) ? "READY_TO_ANALYZE" : "CAPTURING";
                var next = new View(v.id, v.created, v.pluginId, v.parameters, v.steps, assets, state, v.captureProvider);
                Files.move(stage, directory.resolve(expected), ATOMIC_MOVE, REPLACE_EXISTING);
                save(directory, next);
                return next;
            } catch (tools.jackson.core.JacksonException e) { throw bad("Invalid result JSON"); }
            finally { Files.deleteIfExists(stage); }
        } finally { lock.unlock(); }
    }

    private static Asset inspectImage(Path path, String sha, long size, String type) throws IOException {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw bad("Unknown image format");
            var reader = readers.next();
            try {
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!type.equals("image/" + actual)) throw bad("Image format disagrees with its declared type");
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long) w * h > 24_000_000) throw bad("Image dimensions exceed limits");
                var sample = reader.getDefaultReadParam();
                sample.setSourceSubsampling(Math.max(1, w / 256), Math.max(1, h / 256), 0, 0);
                reader.read(0, sample);
                return new Asset(sha, size, type, w, h);
            } catch (javax.imageio.IIOException e) { throw bad("Invalid image header"); }
            finally { reader.dispose(); }
        }
    }

    void delete(String owner, UUID id) throws IOException {
        var lock = lock(owner); lock.lock();
        try {
            var view = get(owner,id);
            if ("photographer-rest".equals(view.captureProvider()) && Files.exists(dir(owner,id).resolve("photometry-capture.json"))
                    && !Files.isRegularFile(dir(owner,id).resolve("camera-closed")))
                throw conflict("Cancel or finish the active camera session before deleting it");
            remove(dir(owner,id));
        } finally { lock.unlock(); }
    }
    static void remove(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private void save(Path directory, View view) throws IOException {
        Path tmp = Files.createTempFile(directory, ".session-", ".tmp");
        try {
            Files.write(tmp, json.writeValueAsBytes(view), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(tmp, directory.resolve("session.json"), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    static long copy(InputStream in, OutputStream out, long max) throws IOException {
        long count = 0;
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            count += n;
            if (count > max) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Byte limit exceeded");
            out.write(buffer, 0, n);
        }
        return count;
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static ResponseStatusException bad(String why) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, why); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Measurement or asset unavailable"); }
    static ResponseStatusException conflict(String why) { return new ResponseStatusException(HttpStatus.CONFLICT, why); }
}

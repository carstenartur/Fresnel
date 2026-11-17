package org.fresnel.backend.measurement.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class MeasurementSessionsTest {
    @TempDir Path directory;
    final JsonMapper json = JsonMapper.builder().build();
    final MeasurementSessions.Plan plan = new MeasurementSessions.Plan("background-oriented-schlieren",
            json.createObjectNode().put("patternSeed", 42), List.of("reference", "disturbed"));

    final ExperimentDocuments documents = new ExperimentDocuments(new org.fresnel.backend.api.FresnelJobService(
            json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator()));

    private MeasurementSessions service() { return new MeasurementSessions(json, directory.toString()); }

    private static byte[] picture(int value) throws IOException {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, value);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static MeasurementSessions.View put(MeasurementSessions s, UUID id, String step, byte[] bytes) throws IOException {
        return s.put("alice", id, step, "image/png", MeasurementSessions.hash(bytes), new ByteArrayInputStream(bytes));
    }

    @Test void videoAndOrderedSequenceRoundTripWithOriginalBytes() throws IOException {
        var service = service();
        var sequence = new MeasurementSessions.Plan(plan.pluginId(), plan.parameters(), List.of("reference","frame-01","frame-02"));
        var seq = service.create("alice", sequence);
        byte[] same = picture(2);
        for (String step : seq.steps()) put(service, seq.id(), step, same);
        var sequenceZip = new ByteArrayOutputStream();
        new MeasurementBundles(service, documents).export("alice", seq.id(), sequenceZip);
        var restoredSequence = new MeasurementBundles(service, documents).read("bob", new ByteArrayInputStream(sequenceZip.toByteArray()));
        assertEquals(sequence.steps(), restoredSequence.steps());
        assertEquals(1, restoredSequence.assets().values().stream().map(MeasurementSessions.Asset::sha256).distinct().count());
        assertThrows(ResponseStatusException.class, () -> service.create("alice",
                new MeasurementSessions.Plan(plan.pluginId(),plan.parameters(),List.of("frame-02","frame-01"))));

        Path fixture = Path.of(System.getProperty("user.dir")).resolve("../frontend/e2e/fixtures/bos-eight-frames.mp4");
        if (!Files.exists(fixture)) fixture = Path.of("frontend/e2e/fixtures/bos-eight-frames.mp4");
        byte[] mp4 = Files.readAllBytes(fixture);
        var video = service.create("alice",new MeasurementSessions.Plan(plan.pluginId(),plan.parameters(),List.of("video")));
        var uploaded = service.put("alice", video.id(), "video", "video/mp4",MeasurementSessions.hash(mp4),new ByteArrayInputStream(mp4));
        assertEquals(144, uploaded.assets().get("video").width());
        assertEquals(128, uploaded.assets().get("video").height());
        var out = new ByteArrayOutputStream();
        new MeasurementBundles(service, documents).export("alice",video.id(),out);
        var restored = new MeasurementBundles(service, documents).read("bob",new ByteArrayInputStream(out.toByteArray()));
        assertArrayEquals(mp4, Files.readAllBytes(service.source("bob",restored.id(),"video").path()));
        var malformed = service.create("alice",new MeasurementSessions.Plan(plan.pluginId(),plan.parameters(),List.of("video")));
        assertThrows(ResponseStatusException.class, () -> service.put("alice",malformed.id(),"video","video/mp4",
                MeasurementSessions.hash(same),new ByteArrayInputStream(same)));
        assertTrue(service.get("alice",malformed.id()).assets().isEmpty());
    }

    @Test void inputSurvivesRestartAndRetryIsImmutableAndPrivate() throws IOException {
        var s = service();
        var id = s.create("alice", plan).id();
        byte[] bytes = picture(128);
        var first = put(s, id, "reference", bytes);
        assertEquals(first, put(service(), id, "reference", bytes));
        assertEquals("CAPTURING", first.state());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> put(s, id, "reference", picture(255))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> s.get("bob", id)).getStatusCode().value());
        assertTrue(s.list("bob").isEmpty());
        assertEquals("READY_TO_ANALYZE", put(s, id, "disturbed", bytes).state());
        assertEquals(List.of(id), service().list("alice").stream().map(MeasurementSessions.View::id).toList());
        s.delete("alice", id);
        assertTrue(s.list("alice").isEmpty());
    }

    @Test void checksumTypeAndPlanValidatedBeforeCommit() throws IOException {
        var s = service();
        var id = s.create("alice", plan).id();
        byte[] bytes = picture(1);
        assertThrows(ResponseStatusException.class, () -> s.put("alice", id, "reference", "image/png",
                "0".repeat(64), new ByteArrayInputStream(bytes)));
        assertThrows(ResponseStatusException.class, () -> s.put("alice", id, "reference", "image/jpeg",
                MeasurementSessions.hash(bytes), new ByteArrayInputStream(bytes)));
        assertThrows(ResponseStatusException.class, () -> put(s, id, "light-01", bytes));
        assertThrows(ResponseStatusException.class, () -> s.create("alice",
                new MeasurementSessions.Plan(plan.pluginId(), plan.parameters(), List.of("reference", "../disturbed"))));
        assertTrue(s.get("alice", id).assets().isEmpty());
    }

    @Test void bundleRoundTripRetainsIdenticalBytesAndLocalOwner() throws IOException {
        var s = service();
        var id = s.create("alice", plan).id();
        byte[] reference = picture(2), disturbed = picture(3);
        put(s, id, "reference", reference);
        put(s, id, "disturbed", disturbed);
        byte[] result = "{\"unit\":\"px\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        s.put("alice", id, "result", "application/json", MeasurementSessions.hash(result), new ByteArrayInputStream(result));
        var out = new ByteArrayOutputStream();
        new MeasurementBundles(s, documents).export("alice", id, out);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            var entries = new HashMap<String,byte[]>();
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) entries.put(e.getName(), zip.readAllBytes());
            assertTrue(new String(entries.get("reports/summary.md"), java.nio.charset.StandardCharsets.UTF_8).contains("Captured steps: 2 of 2"));
            assertFalse(entries.containsKey("job.fresnel"));
        }
        var imported = new MeasurementBundles(service(), documents).read("bob", new ByteArrayInputStream(out.toByteArray()));
        assertNotEquals(id, imported.id());
        assertEquals("COMPLETED", imported.state());
        assertEquals(s.get("alice", id).assets(), imported.assets());
        assertArrayEquals(reference, Files.readAllBytes(s.source("bob", imported.id(), "reference").path()));
        assertArrayEquals(result, Files.readAllBytes(s.source("bob", imported.id(), "result").path()));
    }

    @Test void includesAReopenableTargetJobAndVerifiesItsChecksumOnImport() throws IOException {
        var example = json.readTree(Path.of("..", "examples", "background-oriented-schlieren", "screen-target.fresnel").toFile());
        var settings = json.createObjectNode();
        settings.set("targetParameters", example.required("parameters"));
        var s = service();
        var measured = s.create("alice", new MeasurementSessions.Plan(plan.pluginId(),settings,plan.steps()));
        var bundles = new MeasurementBundles(s, documents);
        var bytes = new ByteArrayOutputStream();
        bundles.export("alice", measured.id(), bytes);
        var imported = bundles.read("bob", new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(settings, imported.parameters());
        var changed = new ByteArrayOutputStream();
        boolean sawJob = false;
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
             var output = new ZipOutputStream(changed)) {
            for (ZipEntry e; (e = input.getNextEntry()) != null;) {
                output.putNextEntry(new ZipEntry(e.getName()));
                byte[] data = input.readAllBytes();
                if (e.getName().equals("job.fresnel")) {
                    sawJob = true;
                    assertEquals("background-oriented-schlieren",json.readTree(data).required("plugin").required("id").asString());
                    output.write(' ');
                }
                output.write(data);
                output.closeEntry();
            }
        }
        assertTrue(sawJob);
        assertThrows(ResponseStatusException.class, () -> bundles.read("bob",new ByteArrayInputStream(changed.toByteArray())));
        assertEquals(1,s.list("bob").size());
    }

    @Test void invalidArchiveRollsBack() throws IOException {
        var s = service();
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../image.png"));
            zip.write(picture(1));
        }
        assertThrows(ResponseStatusException.class, () -> new MeasurementBundles(s, documents).read("alice",
                new ByteArrayInputStream(out.toByteArray())));
        assertTrue(s.list("alice").isEmpty());
    }
}

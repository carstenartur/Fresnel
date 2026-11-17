package org.fresnel.backend.measurement.photographer;

import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Optional acceptance against an independently running Photographer with a dedicated mock camera. */
@EnabledIfEnvironmentVariable(named = "PHOTOGRAPHER_TEST_PAIR_CODE", matches = ".+")
class PhotographerInteropTest {
    @TempDir Path data;

    @Test
    void pairsCapturesBothWorkflowsAndRecoversAcrossProcesses() throws Exception {
        String origin = System.getenv("PHOTOGRAPHER_TEST_ORIGIN");
        var pairing = new PhotographerPairing(origin, false, data.resolve("pairing"));
        pairing.pair(System.getenv("PHOTOGRAPHER_TEST_PAIR_CODE"));
        var provider = new PhotographerCaptureProvider(pairing::current, data.resolve("captures"));
        assertEquals(CaptureProvider.HealthState.CONNECTED, provider.health().state());
        String camera = provider.listDevices().stream().filter(d -> d.supportedFormats().contains(CapturePlan.ImageFormat.PNG))
                .findFirst().orElseThrow().id();
        var bos = bos(camera);
        String createKey = key();
        var ref = provider.createSession(bos, "interop-bos", createKey);
        assertEquals(ref, provider.createSession(bos, "interop-bos", createKey));
        var t = new CaptureProvider.StepTrigger(null, null, Instant.now());
        var triggerKey = key();
        var result = provider.triggerStep(ref.id(), "reference", t, triggerKey);
        // Reconstruct both Fresnel services using only their durable local data.
        var restarted = new PhotographerPairing(origin, false, data.resolve("pairing"));
        provider = new PhotographerCaptureProvider(restarted::current, data.resolve("captures"));
        assertEquals(result, provider.triggerStep(ref.id(), "reference", t, triggerKey));
        var second = provider.triggerStep(ref.id(), "disturbed", new CaptureProvider.StepTrigger(null, null, Instant.now()), key());
        assertNotEquals(result.asset().assetId(), second.asset().assetId());
        assertEquals(CaptureProvider.SessionState.COMPLETED, provider.getSession(ref.id()).state());
        var meta = provider.getAssetMetadata(ref.id(), result.asset().assetId());
        assertEquals(result.asset(), meta);
        int half = Math.toIntExact(meta.byteLength() / 2);
        var merged = new ByteArrayOutputStream();
        try (var first = provider.openAsset(ref.id(), meta.assetId(), new CaptureProvider.ByteRange(0, half))) {
            first.transferTo(merged);
        }
        try (var rest = provider.openAsset(ref.id(), meta.assetId(), new CaptureProvider.ByteRange(half, meta.byteLength() - half))) {
            rest.transferTo(merged);
        }
        assertEquals(meta.sha256(), HexFormat.of().formatHex(PhotographerJournal.sha256().digest(merged.toByteArray())));
        try (var full = provider.openAsset(ref.id(), meta.assetId(), CaptureProvider.FullAsset.INSTANCE)) {
            assertArrayEquals(merged.toByteArray(), full.readAllBytes());
        }
        assertTrue(provider.events(ref.id(), 0).events().stream().anyMatch(event -> meta.equals(event.asset())));

        // Use the exact synthetic red, green and blue raster commitments shared by both projects.
        var fixtureFile = Path.of("../docs/integrations/photographer-capture-v1.fixtures.json");
        var lightPlan = PhotographerHttp.JSON.readTree(Files.readAllBytes(fixtureFile)).at("/values/displayPlan/steps");
        var lights = lightPlan.valueStream().map(light -> new CapturePlan.Step(light.path("id").asText(),
                light.path("sequenceIndex").intValue(), CapturePlan.Role.DIRECTIONAL_LIGHT, CapturePlan.TriggerMode.EXTERNAL,
                Duration.ZERO, light.path("externalPatternId").asText(), light.path("externalPatternSha256").asText())).toList();
        var display = new CapturePlan(CapturePlan.WorkflowType.DISPLAY_PHOTOMETRIC_STEREO, camera, bos.requirements(), lights);
        var displayRef = provider.createSession(display, "interop-lights", key());
        for (var light : lights) {
            var trigger = new CaptureProvider.StepTrigger(light.externalPatternId(), light.externalPatternSha256(), Instant.now());
            assertEquals(light.role(), provider.triggerStep(displayRef.id(), light.id(), trigger, key()).asset().role());
        }
        assertEquals(CaptureProvider.SessionState.COMPLETED, provider.getSession(displayRef.id()).state());

        var recovery = provider.createSession(bos, "interop-recovery", key());
        String originalKey = key();
        var earlier = provider.triggerStep(recovery.id(), "reference", t, originalKey);
        if (System.getenv("PHOTOGRAPHER_TEST_CHECKPOINTS") != null) {
            checkpoint("restart", restarted.current().orElseThrow().pairingId());
            assertEquals(CaptureProvider.SessionState.RECOVERY_REQUIRED, provider.getSession(recovery.id()).state());
            assertEquals(earlier, provider.triggerStep(recovery.id(), "reference", t, originalKey));
            var active = provider;
            assertEquals(CaptureProvider.FailureCode.CONFLICT, assertThrows(CaptureProviderException.class,
                    () -> active.triggerStep(recovery.id(), "disturbed", t, key())).code());
        }
        String cancelKey = key();
        provider.cancel(recovery.id(), cancelKey);
        provider.cancel(recovery.id(), cancelKey);
        assertEquals(CaptureProvider.SessionState.CANCELLED, provider.getSession(recovery.id()).state());
        if (System.getenv("PHOTOGRAPHER_TEST_CHECKPOINTS") != null) {
            checkpoint("revoke", restarted.current().orElseThrow().pairingId());
            assertEquals(CaptureProvider.HealthState.AUTHENTICATION_FAILED, provider.health().state());
        }
    }

    private static void checkpoint(String step, String pairingId) throws Exception {
        Path root = Path.of(System.getenv("PHOTOGRAPHER_TEST_CHECKPOINTS"));
        Files.writeString(root.resolve(step + ".request"), pairingId);
        long end = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!Files.exists(root.resolve(step + ".done"))) {
            if (System.nanoTime() - end >= 0) fail("Timed out at " + step + " checkpoint");
            Thread.sleep(200);
        }
    }

    static CapturePlan bos(String device) {
        return new CapturePlan(CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN, device,
                new CapturePlan.Requirements(false, false, CapturePlan.ImageFormat.PNG),
                List.of(step("reference", 0, CapturePlan.Role.REFERENCE, null),
                        step("disturbed", 1, CapturePlan.Role.DISTURBED, null)));
    }

    private static CapturePlan.Step step(String id, int index, CapturePlan.Role role, String hash) {
        return new CapturePlan.Step(id, index, role, CapturePlan.TriggerMode.EXTERNAL,
                Duration.ZERO, hash == null ? null : id, hash);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}

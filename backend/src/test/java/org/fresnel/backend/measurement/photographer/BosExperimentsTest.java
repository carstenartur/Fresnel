package org.fresnel.backend.measurement.photographer;

import org.fresnel.backend.measurement.CaptureProviderRegistry;
import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BosExperimentsTest {
    @TempDir Path root;

    @Test
    void ownsTheSessionAndImportsEachAcknowledgedImageOnceAcrossRestart() throws Exception {
        var pairings = mock(PhotographerPairing.class);
        var connection = new PhotographerPairing.Connection(URI.create("https://camera.example"),
                "instance", "grant", "secret", List.of(), null);
        when(pairings.withConnection(any())).thenAnswer(i ->
                i.<Function<PhotographerPairing.Connection,Object>>getArgument(0).apply(connection));
        var registry = mock(CaptureProviderRegistry.class);
        var capture = mock(CaptureProvider.class);
        when(registry.require("photographer-rest")).thenReturn(capture);
        when(capture.listDevices()).thenReturn(List.of(new CaptureProvider.Device("wire-camera", "Bench camera",
                CaptureProvider.DeviceState.CONNECTED, Set.of(CaptureProvider.Capability.EXTERNAL_STEP_TRIGGER,
                CaptureProvider.Capability.STILL_CAPTURE), Set.of(CapturePlan.ImageFormat.PNG))));
        when(capture.createSession(any(), anyString(), anyString())).thenAnswer(i ->
                new CaptureProvider.SessionRef("remote", i.getArgument(0), Instant.EPOCH));
        var service = new BosExperiments(pairings, registry, root.toString());
        String camera = service.cameras().getFirst().id();
        assertNotEquals("wire-camera", camera);
        UUID id = UUID.randomUUID();
        assertEquals("WAITING_REFERENCE", service.create("owner", id, camera).state());
        assertEquals(404, assertThrows(PhotographerPairing.Failure.class, () -> service.get("other", id)).status());
        assertTrue(service.list("other").isEmpty());
        assertThrows(PhotographerPairing.Failure.class, () -> service.confirm("other", id, "reference", false));
        assertThrows(PhotographerPairing.Failure.class, () -> service.confirm("owner", id, "disturbed", false));
        var restarted = new BosExperiments(pairings, registry, root.toString());
        restarted.create("owner", id, camera);
        verify(capture).createSession(any(), eq(id.toString()), anyString());
        byte[] bytes = new byte[]{1, 2, 3};
        for (String step : List.of("reference", "disturbed")) {
            var role = CapturePlan.Role.valueOf(step.toUpperCase(java.util.Locale.ROOT));
            var metadata = new CaptureProvider.AssetMetadata("remote", "asset-" + step, step, role, step + ".png",
                    "image/png", bytes.length, HexFormat.of().formatHex(PhotographerJournal.sha256().digest(bytes)), Instant.EPOCH);
            when(capture.triggerStep(eq("remote"), eq(step), any(), anyString()))
                    .thenReturn(new CaptureProvider.StepResult("remote", step, metadata, Instant.EPOCH));
            var download = when(capture.openAsset("remote", "asset-" + step, CaptureProvider.FullAsset.INSTANCE));
            if (step.equals("reference")) {
                download.thenReturn(new ByteArrayInputStream(new byte[]{1}), new ByteArrayInputStream(bytes));
                assertThrows(PhotographerPairing.Failure.class, () -> restarted.confirm("owner", id, step, false));
                assertEquals(step, restarted.get("owner", id).pendingStep());
                var failure = assertThrows(PhotographerPairing.Failure.class, () -> service.confirm("owner", id, step, false));
                assertEquals("CONTINUITY_CONFIRMATION_REQUIRED", failure.getMessage());
            } else {
                download.thenReturn(new ByteArrayInputStream(bytes));
            }
            restarted.confirm("owner", id, step, step.equals("reference"));
            service.confirm("owner", id, step, false);
            var times = org.mockito.ArgumentCaptor.forClass(CaptureProvider.StepTrigger.class);
            var keys = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(capture, times(step.equals("reference") ? 2 : 1)).triggerStep(eq("remote"), eq(step),
                    times.capture(), keys.capture());
            assertEquals(1, times.getAllValues().stream().distinct().count());
            assertEquals(1, keys.getAllValues().stream().distinct().count());
            assertArrayEquals(bytes, Files.readAllBytes(service.source("owner", id, step)));
            assertThrows(PhotographerPairing.Failure.class, () -> service.source("other", id, step));
        }
        assertEquals("COMPLETED", service.get("owner", id).state());
        assertEquals(1, service.list("owner").size());
    }
}

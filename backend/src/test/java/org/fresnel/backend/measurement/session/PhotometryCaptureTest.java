package org.fresnel.backend.measurement.session;

import org.fresnel.backend.measurement.photographer.PhotometerCameras;
import org.fresnel.measurement.*;
import org.fresnel.measurement.photometry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.fresnel.measurement.CaptureProvider.Capability.*;

class PhotometryCaptureTest {
    @TempDir Path data;
    final CaptureProvider provider = mock(CaptureProvider.class);
    final PhotometerCameras cameras = mock(PhotometerCameras.class);
    final DisplayGeometry geometry = new DisplayGeometry(480,300,128,80,310,0,0,4,8,100,2.2);
    final UUID id = UUID.randomUUID();
    MeasurementSessions sessions;
    PhotometryCapture service;
    String camera;
    byte[] bytes;

    @BeforeEach void setup() throws IOException {
        restart();
        var capabilities = Set.of(STILL_CAPTURE,EXTERNAL_STEP_TRIGGER,DURABLE_MEDIA,SHA256_INTEGRITY);
        var formats = Set.of(CapturePlan.ImageFormat.PNG);
        var descriptor = new CaptureProvider.Descriptor("photographer-rest","Test",1,1,capabilities,formats,
                new CaptureProvider.Limits(18,1<<20,Duration.ofMinutes(15)));
        var grant = new PhotometerCameras.Grant("test-grant",descriptor,provider);
        when(cameras.use(any())).thenAnswer(call -> call.<Function<PhotometerCameras.Grant,Object>>getArgument(0).apply(grant));
        when(provider.listDevices()).thenReturn(List.of(new CaptureProvider.Device("private-id","Test camera",CaptureProvider.DeviceState.CONNECTED,capabilities,formats)));
        camera = service.cameras().getFirst().id();
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"PNG",image);
        bytes = image.toByteArray();
        when(provider.createSession(any(),anyString(),anyString())).thenAnswer(call ->
                new CaptureProvider.SessionRef("remote-session",call.getArgument(0),Instant.now()));
        when(provider.triggerStep(anyString(),anyString(),any(),anyString())).thenAnswer(call -> {
            String step = call.getArgument(1);
            var role = step.equals("flat") ? CapturePlan.Role.FLAT : step.equals("dark") ? CapturePlan.Role.DARK : CapturePlan.Role.DIRECTIONAL_LIGHT;
            var a = new CaptureProvider.AssetMetadata("remote-session","asset-"+step,step,role,"source.png","image/png",
                    bytes.length, MeasurementSessions.hash(bytes), Instant.now());
            return new CaptureProvider.StepResult("remote-session",step,a,Instant.now());
        });
        when(provider.openAsset(anyString(),anyString(),any())).thenAnswer(call -> new ByteArrayInputStream(bytes));
    }
    void restart() {
        sessions = new MeasurementSessions(JsonMapper.builder().build(), data.toString());
        service = new PhotometryCapture(sessions,cameras);
    }
    PhotometryCapture.Selection selection(boolean manual) { return new PhotometryCapture.Selection(camera,geometry,manual); }

    @Test void capturesOnlyAfterCorrectPresentationAndContinuityConfirmation() throws IOException {
        assertThrows(ResponseStatusException.class, () -> service.create("alice",id,selection(false)));
        var created = service.create("alice",id,selection(true));
        assertEquals("dark",created.nextStep());
        assertEquals(created,service.create("alice",id,selection(true)));
        verify(provider,times(1)).createSession(any(),anyString(),anyString());
        assertThrows(ResponseStatusException.class, () -> sessions.delete("alice",id));
        assertThrows(ResponseStatusException.class, () -> service.get("bob",id));
        assertTrue(service.list("bob").isEmpty());
        assertThrows(ResponseStatusException.class, () -> sessions.put("alice",id,"dark","image/png",
                MeasurementSessions.hash(bytes),new ByteArrayInputStream(bytes)));
        var sequence = DisplayIlluminationSequenceGenerator.generate(geometry);
        for (var pattern : sequence.patterns()) {
            var p = new PhotometryCapture.Presentation(pattern.pixelSha256(),UUID.randomUUID());
            assertThrows(ResponseStatusException.class, () -> service.trigger("alice",id,pattern.id(),
                    new PhotometryCapture.Presentation("0".repeat(64),p.presentationId())));
            var triggered = service.trigger("alice",id,pattern.id(),p);
            assertEquals(pattern.id(),triggered.pendingStep());
            assertEquals(triggered,service.trigger("alice",id,pattern.id(),p));
            assertFalse(sessions.get("alice",id).assets().containsKey(pattern.id()));
            assertThrows(ResponseStatusException.class, () -> service.accept("alice",id,pattern.id(),
                    new PhotometryCapture.Presentation(pattern.pixelSha256(),UUID.randomUUID())));
            var accepted = service.accept("alice",id,pattern.id(),p);
            restart();
            assertEquals(accepted,service.accept("alice",id,pattern.id(),p));
        }
        assertEquals("COMPLETED",service.get("alice",id).state());
        assertEquals("READY_TO_ANALYZE",sessions.get("alice",id).state());
        assertEquals(6,sessions.get("alice",id).assets().size());
        verify(provider,times(6)).triggerStep(anyString(),anyString(),any(),anyString());
        assertEquals(service.get("alice",id),service.list("alice").getFirst());
        var json = JsonMapper.builder().build();
        try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var documents = new ExperimentDocuments(new org.fresnel.backend.api.FresnelJobService(json,validation.getValidator()));
            var bundles = new MeasurementBundles(sessions,documents);
            var bytes = new ByteArrayOutputStream();
            bundles.export("alice",id,bytes);
            var restored = bundles.read("bob",new ByteArrayInputStream(bytes.toByteArray()));
            var original = CaptureEvidence.read(sessions.dir("alice",id),json);
            assertEquals(original,CaptureEvidence.read(sessions.dir("bob",restored.id()),json));
            var targetId = UUID.randomUUID();
            var plan = new MeasurementSessions.Plan(restored.pluginId(),
                    json.valueToTree(Map.of("geometry",geometry, "roi",Map.of("width",0.5))),restored.steps());
            var hashes = new TreeMap<String,String>();
            restored.assets().forEach((step,asset) -> hashes.put(step,asset.sha256()));
            var request = new MeasurementSessions.Derivation(plan,hashes);
            assertThrows(ResponseStatusException.class, () -> sessions.derive("alice",targetId,restored.id(),request));
            hashes.put("dark","0".repeat(64));
            assertThrows(ResponseStatusException.class, () -> sessions.derive("bob",targetId,restored.id(),request));
            assertThrows(ResponseStatusException.class, () -> sessions.get("bob",targetId));
            hashes.put("dark",restored.assets().get("dark").sha256());
            var different = json.valueToTree(geometry).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) different).put("widthPx",256);
            var otherPlan = new MeasurementSessions.Plan(plan.pluginId(),different,restored.steps());
            assertThrows(ResponseStatusException.class, () -> sessions.derive("bob",targetId,restored.id(),
                    new MeasurementSessions.Derivation(otherPlan,hashes)));
            var derived = sessions.derive("bob",targetId,restored.id(),request);
            restart();
            assertEquals(derived,sessions.derive("bob",targetId,restored.id(),request));
            byte[] result = "{\"validPixels\":64}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("COMPLETED", sessions.put("bob",targetId,"result","application/json",
                    MeasurementSessions.hash(result),new ByteArrayInputStream(result)).state());
            sessions.delete("bob",restored.id());
            var bundle = new ByteArrayOutputStream();
            bundles.export("bob",targetId,bundle);
            var analyzed = bundles.read("carol",new ByteArrayInputStream(bundle.toByteArray()));
            assertEquals(original,CaptureEvidence.read(sessions.dir("carol",analyzed.id()),json));
            assertEquals("COMPLETED",analyzed.state());
            assertTrue(original.manualLocksConfirmed());
            assertEquals(sequence.sequenceId(),original.sequenceId());
            assertEquals(6,original.exposures().size());
            assertEquals("manual-upload",restored.captureProvider());
            assertFalse(service.list("bob").stream().anyMatch(x -> x.id().equals(restored.id())));
        }
        sessions.delete("alice",id);
    }

    @Test void losingPresentationDiscardsUnconfirmedExposure() throws IOException {
        service.create("alice",id,selection(true));
        var pattern = DisplayIlluminationSequenceGenerator.generate(geometry).patterns().getFirst();
        var p = new PhotometryCapture.Presentation(pattern.pixelSha256(),UUID.randomUUID());
        service.trigger("alice",id,"dark",p);
        restart();
        assertThrows(ResponseStatusException.class, () -> service.trigger("alice",id,"dark",
                new PhotometryCapture.Presentation(pattern.pixelSha256(),UUID.randomUUID())));
        assertEquals("CANCELLED",service.cancel("alice",id).state());
        assertEquals("CANCELLED",service.cancel("alice",id).state());
        verify(provider,times(1)).cancel(anyString(),anyString());
        assertThrows(ResponseStatusException.class, () -> service.accept("alice",id,"dark",p));
        assertTrue(sessions.get("alice",id).assets().isEmpty());
        sessions.delete("alice",id);
    }
}

package org.fresnel.backend.measurement.session;

import org.fresnel.backend.measurement.photographer.PhotometerCameras;
import org.fresnel.measurement.*;
import org.fresnel.measurement.photometry.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.fresnel.measurement.CaptureProvider.Capability.*;
import static org.fresnel.measurement.CapturePlan.ImageFormat.PNG;
import static org.fresnel.backend.measurement.session.MeasurementSessions.*;

@Service
@ConditionalOnProperty(name="fresnel.capture.photographer.enabled", havingValue="true")
class PhotometryCapture {
    static final String PLUGIN = "display-photometric-stereo", PROVIDER = "photographer-rest";
    final MeasurementSessions sessions;
    final PhotometerCameras cameras;

    PhotometryCapture(MeasurementSessions sessions, PhotometerCameras cameras) { this.sessions = sessions; this.cameras = cameras; }
    record Camera(String id, String name) {}
    record Selection(String cameraId, DisplayGeometry geometry, boolean manualLocksConfirmed) {}
    record Presentation(String pixelSha256, UUID presentationId) {}
    record View(UUID id, String sequenceId, String state, String nextStep, String pendingStep, List<String> acceptedSteps, UUID archiveId) {}
    record Observed(CaptureProvider.AssetMetadata asset, Instant completedAt) {}
    static final class Journal {
        public String sequenceId, binding, camera, createKey, remote;
        public boolean manualLocksConfirmed, cancelled, cancelling;
        public CapturePlan plan;
        public String pending;
        public UUID presentation;
        public Instant presentedAt;
        public Observed observed;
        public Map<String,Observed> accepted = new LinkedHashMap<>();
        View view(UUID id) {
            String next = plan.steps().stream().map(CapturePlan.Step::id).filter(s -> !accepted.containsKey(s)).findFirst().orElse(null);
            String state = cancelled ? "CANCELLED" : cancelling ? "CANCELLING" : remote == null ? "CREATING" :
                    next == null ? "COMPLETED" : pending != null ? "AWAITING_ACCEPTANCE" : "READY";
            return new View(id, sequenceId, state, cancelled || cancelling ? null : next, pending, List.copyOf(accepted.keySet()), id);
        }
    }

    List<Camera> cameras() {
        return cameras.use(g -> g.devices().stream().map(d -> new Camera(g.selection(d.id()), d.displayName())).toList());
    }
    List<View> list(String owner) throws IOException {
        var result = new ArrayList<View>();
        for (var v : sessions.list(owner)) if (PROVIDER.equals(v.captureProvider()) && PLUGIN.equals(v.pluginId())
                && Files.isRegularFile(journal(owner,v.id()))) result.add(read(owner,v.id()).view(v.id()));
        return result;
    }
    View get(String owner, UUID id) throws IOException { return read(owner,id).view(id); }

    View create(String owner, UUID id, Selection selection) {
        if (selection == null || selection.geometry == null || selection.cameraId == null) throw bad("Missing geometry or camera");
        return operate(owner, g -> {
            Path file = journal(owner,id);
            Journal j;
            if (Files.isRegularFile(file)) {
                j = read(owner,id);
                if (!j.camera.equals(selection.cameraId) || j.manualLocksConfirmed != selection.manualLocksConfirmed ||
                        !sessions.get(owner,id).parameters().equals(sessions.json.valueToTree(selection.geometry)))
                    throw conflict("Capture settings differ from the saved plan");
                if (j.cancelled || j.cancelling || j.remote != null) return j.view(id);
                checkGrant(j,g);
            } else {
                var device = g.devices().stream().filter(d -> g.selection(d.id()).equals(selection.cameraId)).findFirst()
                        .orElseThrow(() -> conflict("Selected camera is unavailable"));
                for (var requirement : List.of(STILL_CAPTURE, EXTERNAL_STEP_TRIGGER, DURABLE_MEDIA, SHA256_INTEGRITY))
                    if (!g.descriptor().supports(requirement)) throw conflict("Photographer lacks " + requirement);
                boolean exposure = g.descriptor().supports(EXPOSURE_LOCK) && device.supports(EXPOSURE_LOCK);
                boolean focus = g.descriptor().supports(FOCUS_LOCK) && device.supports(FOCUS_LOCK);
                if ((!exposure || !focus) && !selection.manualLocksConfirmed) throw conflict("Confirm manually locked exposure and focus");
                var manifest = DisplayIlluminationSequenceGenerator.generate(selection.geometry);
                var patterns = manifest.patterns();
                if (patterns.size() > g.descriptor().limits().maximumSteps()) throw conflict("Sequence exceeds the negotiated camera limit");
                var steps = new ArrayList<CapturePlan.Step>();
                for (var p : patterns) steps.add(new CapturePlan.Step(p.id(), steps.size(), CapturePlan.Role.valueOf(p.role().name()),
                        CapturePlan.TriggerMode.EXTERNAL, Duration.ofMillis(selection.geometry.settleDelayMs()), p.id(), p.pixelSha256()));
                var plan = new CapturePlan(CapturePlan.WorkflowType.DISPLAY_PHOTOMETRIC_STEREO, device.id(),
                        new CapturePlan.Requirements(exposure,focus,PNG), steps);
                sessions.create(owner, id, new MeasurementSessions.Plan(PLUGIN,sessions.json.valueToTree(selection.geometry),
                        steps.stream().map(CapturePlan.Step::id).toList()), PROVIDER);
                j = new Journal(); j.sequenceId = manifest.sequenceId(); j.camera = selection.cameraId;
                j.binding = g.binding(); j.manualLocksConfirmed = selection.manualLocksConfirmed;
                j.plan = plan; j.createKey = UUID.randomUUID().toString();
                save(file,j);
            }
            createRemote(j,g,id,file);
            return j.view(id);
        });
    }

    View trigger(String owner, UUID id, String step, Presentation p) {
        return operate(owner, g -> {
            var j = read(owner,id);
            checkGrant(j,g);
            var planned = step(j, step, p);
            if (j.cancelled || j.cancelling || j.remote == null || !step.equals(j.view(id).nextStep())) throw conflict("Step is not ready");
            if (j.pending != null) {
                if (!step.equals(j.pending) || !p.presentationId.equals(j.presentation)) throw conflict("Presentation changed; cancel this sequence");
                if (j.observed != null) return j.view(id);
            } else {
                j.pending = step; j.presentation = p.presentationId; j.presentedAt = Instant.now(); save(journal(owner,id),j);
            }
            var result = g.provider().triggerStep(j.remote,step,new CaptureProvider.StepTrigger(step,planned.externalPatternSha256(),j.presentedAt),
                    j.createKey + "-" + step);
            var a = result.asset();
            if (!j.remote.equals(a.sessionId()) || !step.equals(a.stepId()) || planned.role() != a.role() ||
                    !"image/png".equals(a.mediaType()) || a.byteLength() > IMAGE_BYTES) throw conflict("Unexpected capture asset");
            j.observed = new Observed(a,result.completedAt()); save(journal(owner,id),j);
            return j.view(id);
        });
    }

    View accept(String owner, UUID id, String step, Presentation p) {
        return operate(owner,g -> {
            var j = read(owner,id);
            step(j,step,p);
            if (j.accepted.containsKey(step)) return finish(owner,id,j);
            checkGrant(j,g);
            if (j.cancelled || j.cancelling || !step.equals(j.pending) || !p.presentationId.equals(j.presentation) || j.observed == null)
                throw conflict("An uninterrupted presented exposure is required");
            var a = j.observed.asset();
            try (var source = g.provider().openAsset(j.remote,a.assetId(),CaptureProvider.FullAsset.INSTANCE)) {
                sessions.put(owner,id,step,a.mediaType(),a.sha256(),source,PROVIDER);
            }
            j.accepted.put(step,j.observed); j.observed = null; j.pending = null; j.presentation = null; j.presentedAt = null;
            save(journal(owner,id),j);
            return finish(owner,id,j);
        });
    }

    View cancel(String owner, UUID id) {
        return operate(owner,g -> {
            var j = read(owner,id);
            if (j.cancelled || j.view(id).nextStep() == null) return finish(owner,id,j);
            checkGrant(j,g);
            j.cancelling = true; save(journal(owner,id),j);
            if (j.remote == null) createRemote(j,g,id,journal(owner,id));
            g.provider().cancel(j.remote,j.createKey+"-cancel");
            j.cancelled = true; j.cancelling = false; j.pending = null; j.presentation = null; j.observed = null;
            save(journal(owner,id),j); return finish(owner,id,j);
        });
    }
    private View finish(String owner, UUID id, Journal j) throws IOException {
        View v = j.view(id);
        if (j.cancelled || v.nextStep == null) Files.write(sessions.dir(owner,id).resolve("camera-closed"),new byte[0]);
        return v;
    }
    private static CapturePlan.Step step(Journal j,String step,Presentation p) {
        if (p == null || p.presentationId == null) throw bad("Missing presentation identity");
        return j.plan.steps().stream().filter(s -> s.id().equals(step) && s.externalPatternSha256().equals(p.pixelSha256()))
                .findFirst().orElseThrow(() -> conflict("Unexpected pattern ID or pixel hash"));
    }
    private static void checkGrant(Journal j, PhotometerCameras.Grant g) {
        if (!j.binding.equals(g.binding())) throw conflict("The original camera pairing has changed");
    }
    private void createRemote(Journal j, PhotometerCameras.Grant g, UUID id, Path file) throws IOException {
        j.remote = g.provider().createSession(j.plan,id.toString(),j.createKey).id(); save(file,j);
    }

    private Path journal(String owner,UUID id) { return sessions.dir(owner,id).resolve("photometry-capture.json"); }
    private Journal read(String owner,UUID id) throws IOException {
        try { return sessions.json.readValue(Files.readAllBytes(journal(owner,id)),Journal.class); }
        catch (NoSuchFileException e) { throw missing(); }
    }
    private void save(Path file, Journal j) throws IOException {
        var tmp = Files.createTempFile(file.getParent(),".camera-",".tmp");
        try {
            Files.write(tmp,sessions.json.writeValueAsBytes(j), StandardOpenOption.WRITE,StandardOpenOption.SYNC);
            Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }
    @FunctionalInterface private interface CameraOperation { View run(PhotometerCameras.Grant g) throws IOException; }
    private View operate(String owner, CameraOperation op) {
        var lock = sessions.lock(owner); lock.lock();
        try {
            return cameras.use(g -> {
                try { return op.run(g); }
                catch (IOException e) { throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,"Camera session storage unavailable"); }
            });
        } finally { lock.unlock(); }
    }
}

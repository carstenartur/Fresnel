package org.fresnel.backend.measurement.photographer;

import org.fresnel.backend.desktop.DesktopDataDirectory;
import org.fresnel.backend.measurement.CaptureProviderRegistry;
import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.fresnel.measurement.CapturePlan.ImageFormat.PNG;
import static org.fresnel.measurement.CapturePlan.Role.DISTURBED;
import static org.fresnel.measurement.CapturePlan.Role.REFERENCE;

/** Owner-private BOS experiments and verified local source images. Remote identities stay server-side. */
@Service
@ConditionalOnProperty(name = "fresnel.capture.photographer.enabled", havingValue = "true")
final class BosExperiments {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path directory;
    private final PhotographerPairing pairings;
    private final CaptureProviderRegistry providers;

    BosExperiments(PhotographerPairing pairings, CaptureProviderRegistry providers,
                   @Value("${fresnel.capture.photographer.experiments-directory:}") String directory) {
        this.pairings = pairings;
        this.providers = providers;
        Path root = directory.isBlank() ? DesktopDataDirectory.resolve().resolve("bos-captures") : Path.of(directory);
        this.directory = root.toAbsolutePath().normalize();
    }

    private static final int MAX_FRAMES = 24;
    private static final long MAX_IMAGE = 20L << 20, MAX_TOTAL = 128L << 20;

    record Camera(String id, String name, int maximumDisturbedFrames, boolean exposureLock, boolean focusLock) {}
    record Image(long byteLength, String sha256, Instant capturedAt) {}
    record View(UUID id, String state, Image reference, Image disturbed, String pendingStep, String cameraSelection,
                String nextStep, Map<String,Image> frames, int disturbedCount, boolean lockExposure, boolean lockFocus) {}

    private static final class Experiment {
        public UUID id;
        public String pairing;
        public String selection;
        public String device;
        public String createKey;
        public String session;
        public boolean cancelled;
        public Instant referenceAck;
        public Instant disturbedAck;
        public Image reference;
        public Image disturbed;
        public int disturbedCount = 1;
        public boolean lockExposure;
        public boolean lockFocus;
        public Map<String,Image> frames = new LinkedHashMap<>();

        String nextStep() {
            if (cancelled || session == null) return null;
            if (reference == null) return "reference";
            if (disturbedCount == 1) return disturbed == null ? "disturbed" : null;
            return frames.size() < disturbedCount ? frameName(frames.size() + 1) : null;
        }
        View view() {
            String next = nextStep();
            String state = cancelled ? "CANCELLED" : session == null ? "CREATING" : next == null ? "COMPLETED"
                    : reference == null ? "WAITING_REFERENCE" : "WAITING_DISTURBED";
            Instant ack = reference == null ? referenceAck : disturbedAck;
            return new View(id, state, reference, disturbed, ack == null ? null : next, selection, next,
                    Map.copyOf(frames), disturbedCount, lockExposure, lockFocus);
        }
        Image image(String step) {
            return switch (step) {
                case "reference" -> reference;
                case "disturbed" -> disturbed;
                default -> frames.get(step);
            };
        }
    }

    List<Camera> cameras() {
        return pairings.withConnection(c -> {
            var descriptor = provider().descriptor();
            return available().stream().map(d -> camera(binding(c),d,descriptor))
                    .filter(d -> d.maximumDisturbedFrames() >= 1).toList();
        });
    }

    private static Camera camera(String binding, CaptureProvider.Device device, CaptureProvider.Descriptor provider) {
        return new Camera(selection(binding, device.id()), device.displayName(), Math.min(MAX_FRAMES, provider.limits().maximumSteps()-1),
                device.supports(CaptureProvider.Capability.EXPOSURE_LOCK) && provider.supports(CaptureProvider.Capability.EXPOSURE_LOCK),
                device.supports(CaptureProvider.Capability.FOCUS_LOCK) && provider.supports(CaptureProvider.Capability.FOCUS_LOCK));
    }

    List<View> list(String owner) {
        Path parent = ownerDirectory(owner);
        if (Files.notExists(parent)) return List.of();
        try (var files = Files.list(parent)) {
            return files.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.resolve("experiment.json")).filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(BosExperiments::load).map(Experiment::view)
                    .sorted(Comparator.comparing(View::id)).toList();
        } catch (IOException ex) { throw storeFailure(); }
    }

    View get(String owner, UUID id) { return load(manifest(owner, id)).view(); }

    View create(String owner, UUID id, String cameraId) {
        return create(owner,id,cameraId,1,false,false);
    }

    View create(String owner, UUID id, String cameraId, int count, boolean lockExposure, boolean lockFocus) {
        if (count < 1 || count > MAX_FRAMES) throw failure(400,"INVALID_FRAME_COUNT");
        return pairings.withConnection(c -> {
            String binding = binding(c);
            Path file = manifest(owner, id);
            Experiment e;
            if (Files.exists(file)) {
                e = load(file);
                if (!e.selection.equals(cameraId) || e.disturbedCount != count || e.lockExposure != lockExposure || e.lockFocus != lockFocus) throw failure(409, "EXPERIMENT_CAMERA_MISMATCH");
                requireBinding(e, c);
                if (e.session != null || e.cancelled) return e.view();
            } else {
                var device = available().stream().filter(d -> selection(binding, d.id()).equals(cameraId))
                        .findFirst().orElseThrow(() -> failure(409, "CAMERA_UNAVAILABLE"));
                var allowed = camera(binding,device,provider().descriptor());
                if (count > allowed.maximumDisturbedFrames() || lockExposure && !allowed.exposureLock()
                        || lockFocus && !allowed.focusLock()) throw failure(409,"UNSUPPORTED_CAPTURE_PLAN");
                e = new Experiment();
                e.disturbedCount = count;
                e.lockExposure = lockExposure;
                e.lockFocus = lockFocus;
                e.id = id;
                e.pairing = binding;
                e.selection = cameraId;
                e.device = device.id();
                e.createKey = UUID.randomUUID().toString();
                save(file, e);
            }
            createRemote(file, e);
            return e.view();
        });
    }

    View confirm(String owner, UUID id, String step, boolean continuous) {
        checkStepName(step);
        var role = step.equals("reference") ? REFERENCE : DISTURBED;
        Path file = manifest(owner, id);
        var latest = load(file);
        if (latest.image(step) != null) return latest.view();
        return pairings.withConnection(c -> {
            var e = load(file);
            if (e.image(step) != null) return e.view();
            requireBinding(e, c);
            if (!step.equals(e.nextStep()))
                throw failure(409, "STEP_NOT_READY");
            Instant at = role == REFERENCE ? e.referenceAck : e.disturbedAck;
            if (at != null && !continuous) throw failure(409, "CONTINUITY_CONFIRMATION_REQUIRED");
            if (at == null) {
                at = Instant.now();
                if (role == REFERENCE) e.referenceAck = at; else e.disturbedAck = at;
                save(file, e);
            }
            // The create key and step uniquely determine the trigger key, including across restarts.
            var result = provider().triggerStep(e.session, step, new CaptureProvider.StepTrigger(null, null, at),
                    e.createKey + "-" + step);
            var metadata = result.asset();
            if (!metadata.sessionId().equals(e.session) || !metadata.stepId().equals(step) || metadata.role() != role
                    || !"image/png".equals(metadata.mediaType())) throw failure(502, "ASSET_MISMATCH");
            long previousBytes = e.frames.values().stream().mapToLong(Image::byteLength).sum()
                    + (e.reference == null ? 0 : e.reference.byteLength());
            if (metadata.byteLength() > MAX_IMAGE || metadata.byteLength() > MAX_TOTAL - previousBytes)
                throw failure(413, "CAPTURE_IMAGE_LIMIT");
            try (var source = provider().openAsset(e.session, metadata.assetId(), CaptureProvider.FullAsset.INSTANCE)) {
                importImage(imagePath(file, step), metadata, source);
            } catch (IOException ex) { throw storeFailure(); }
            var imported = new Image(metadata.byteLength(), metadata.sha256(), metadata.capturedAt());
            if (role == REFERENCE) e.reference = imported;
            else {
                if (e.disturbedCount == 1) e.disturbed = imported;
                else e.frames.put(step, imported);
                e.disturbedAck = null;
            }
            save(file, e);
            return e.view();
        });
    }

    View cancel(String owner, UUID id) {
        Path file = manifest(owner, id);
        Experiment latest = load(file);
        if (latest.cancelled || "COMPLETED".equals(latest.view().state())) return latest.view();
        return pairings.withConnection(c -> {
            Experiment e = load(file);
            if (e.cancelled || "COMPLETED".equals(e.view().state())) return e.view();
            requireBinding(e, c);
            // Reconcile a lost create response with its original key before cancelling the remote lease.
            if (e.session == null) createRemote(file, e);
            provider().cancel(e.session, e.createKey + "-cancel");
            e.cancelled = true;
            save(file, e);
            return e.view();
        });
    }

    Path source(String owner, UUID id, String step) {
        Path file = manifest(owner, id);
        checkStepName(step);
        if (load(file).image(step) == null) throw failure(404, "IMAGE_NOT_FOUND");
        Path source = imagePath(file, step);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw failure(404, "IMAGE_NOT_FOUND");
        return source;
    }

    private List<CaptureProvider.Device> available() {
        return provider().listDevices().stream().filter(d -> d.state() == CaptureProvider.DeviceState.CONNECTED
                && d.supportedFormats().contains(PNG) && d.supports(CaptureProvider.Capability.EXTERNAL_STEP_TRIGGER)
                && d.supports(CaptureProvider.Capability.STILL_CAPTURE)).toList();
    }

    private void createRemote(Path file, Experiment e) {
        var steps = new ArrayList<CapturePlan.Step>();
        steps.add(new CapturePlan.Step("reference", 0, REFERENCE, CapturePlan.TriggerMode.EXTERNAL, Duration.ZERO, null, null));
        for (int i = 1; i <= e.disturbedCount; i++) {
            String name = e.disturbedCount == 1 ? "disturbed" : frameName(i);
            steps.add(new CapturePlan.Step(name, i, DISTURBED, CapturePlan.TriggerMode.EXTERNAL, Duration.ZERO, null, null));
        }
        var plan = new CapturePlan(CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN, e.device,
                new CapturePlan.Requirements(e.lockExposure, e.lockFocus, PNG), steps);
        e.session = provider().createSession(plan, e.id.toString(), e.createKey).id();
        save(file, e);
    }

    private CaptureProvider provider() { return providers.require(PhotographerCaptureProvider.ID); }

    private static void importImage(Path target, CaptureProvider.AssetMetadata meta, InputStream source) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".image-", ".tmp");
        try {
            var digest = PhotographerJournal.sha256();
            try (var input = new DigestInputStream(source, digest); var out = Files.newOutputStream(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
                byte[] buf = new byte[8192];
                long length = 0;
                int size;
                while ((size = input.read(buf)) != -1) {
                    length += size;
                    if (length > meta.byteLength()) throw failure(502, "ASSET_MISMATCH");
                    out.write(buf, 0, size);
                }
                if (length != meta.byteLength() || !meta.sha256().equals(HexFormat.of().formatHex(digest.digest())))
                    throw failure(502, "ASSET_MISMATCH");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String frameName(int index) { return String.format(Locale.ROOT, "frame-%02d", index); }
    private static void checkStepName(String step) {
        if (!"reference".equals(step) && !"disturbed".equals(step)
                && (step == null || !step.matches("frame-(0[1-9]|1[0-9]|2[0-4])")))
            throw failure(404,"STEP_NOT_FOUND");
    }
    private static Path imagePath(Path manifest, String step) {
        checkStepName(step);
        return child(manifest.getParent(), step + ".png");
    }

    private Path ownerDirectory(String owner) { return child(directory, hash(owner)); }

    private Path manifest(String owner, UUID id) {
        Path experimentDirectory = child(ownerDirectory(owner), id.toString());
        return child(experimentDirectory, "experiment.json");
    }

    private static Path child(Path parent, String name) {
        Path path = parent.resolve(name).normalize();
        if (!path.startsWith(parent) || !parent.equals(path.getParent()) || Files.isSymbolicLink(path))
            throw failure(404, "EXPERIMENT_NOT_FOUND");
        return path;
    }
    private static String binding(PhotographerPairing.Connection c) { return hash(c.origin() + "\n" + c.instanceId() + "\n" + c.pairingId()); }
    private static String selection(String binding, String device) { return hash(binding + "\n" + device); }
    private static String hash(String text) {
        return HexFormat.of().formatHex(PhotographerJournal.sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void requireBinding(Experiment e, PhotographerPairing.Connection c) {
        if (!binding(c).equals(e.pairing)) throw failure(409, "EXPERIMENT_PAIRING_CHANGED");
    }

    private static Experiment load(Path file) {
        try { return JSON.readValue(Files.readAllBytes(file), Experiment.class); }
        catch (NoSuchFileException missing) { throw failure(404, "EXPERIMENT_NOT_FOUND"); }
        catch (IOException | tools.jackson.core.JacksonException ex) { throw storeFailure(); }
    }

    private static void save(Path file, Experiment e) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".experiment-", ".tmp");
            try {
                Files.write(tmp, JSON.writeValueAsBytes(e), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException ex) { throw storeFailure(); }
    }

    private static PhotographerPairing.Failure failure(int status, String code) { return new PhotographerPairing.Failure(status, code); }
    private static PhotographerPairing.Failure storeFailure() { return failure(500, "EXPERIMENT_STORE_UNAVAILABLE"); }
}

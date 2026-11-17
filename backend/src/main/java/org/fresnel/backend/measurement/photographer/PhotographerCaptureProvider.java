package org.fresnel.backend.measurement.photographer;

import org.fresnel.backend.desktop.DesktopDataDirectory;
import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.fresnel.backend.measurement.photographer.PhotographerHttp.*;

/** Server-side adapter for the pinned Photographer v1 still-capture protocol. */
@Component
@ConditionalOnProperty(prefix = "fresnel.capture.photographer", name = "enabled", havingValue = "true")
public final class PhotographerCaptureProvider implements CaptureProvider {
    public static final String ID = "photographer-rest";
    private static final Descriptor UNPAIRED = new Descriptor(ID, "Photographer", 1, 1, Set.of(), Set.of(),
            new Limits(1, 1, Duration.ofSeconds(1)));
    private final Supplier<Optional<PhotographerPairing.Connection>> pairing;
    private final PhotographerJournal journal;

    @Autowired
    public PhotographerCaptureProvider(PhotographerPairing pairing,
            @Value("${fresnel.capture.photographer.journal-directory:}") String directory) {
        this(pairing::current, directory.isBlank() ? DesktopDataDirectory.resolve().resolve("photographer-captures")
                : Path.of(directory));
    }

    PhotographerCaptureProvider(Supplier<Optional<PhotographerPairing.Connection>> pairing, Path directory) {
        this.pairing = pairing;
        this.journal = new PhotographerJournal(directory);
    }

    @Override
    public Descriptor descriptor() {
        return pairing.get().map(PhotographerPairing.Connection::descriptor).orElse(UNPAIRED);
    }

    public static Descriptor descriptor(JsonNode wire) {
        int major = wire.required("protocolVersion").intValue();
        int minimum = wire.required("minimumSupportedProtocolVersion").intValue();
        boolean polls = wire.required("eventTransports").valueStream().anyMatch(n -> "POLLING".equals(n.asText()));
        JsonNode limits = wire.required("limits");
        if (major < 1 || minimum != 1 || !polls || limits.required("idempotencyRetentionSeconds").longValue() < 86_400) {
            throw incompatible();
        }
        return new Descriptor(ID, text(wire, "displayName"), major, minimum, known(wire, "capabilities", Capability.class),
                known(wire, "supportedFormats", CapturePlan.ImageFormat.class),
                new Limits(limits.required("maximumSteps").intValue(), limits.required("maximumAssetBytes").longValue(),
                        Duration.ofMillis(limits.required("maximumSessionDurationMs").longValue())));
    }

    @Override
    public Health health() {
        var configured = pairing.get();
        if (configured.isEmpty()) return new Health(HealthState.NOT_CONFIGURED, Instant.now(), "PHOTOGRAPHER_NOT_PAIRED");
        try {
            var wire = json(configured.get(), "/health", null, null, 200).body();
            return new Health(HealthState.valueOf(text(wire, "state")), Instant.now(), text(wire, "messageCode"));
        } catch (CaptureProviderException ex) {
            var state = switch (ex.code()) {
                case AUTHENTICATION_FAILED -> HealthState.AUTHENTICATION_FAILED;
                case INCOMPATIBLE -> HealthState.INCOMPATIBLE;
                default -> HealthState.DISCONNECTED;
            };
            return new Health(state, Instant.now(), ex.messageCode());
        } catch (IllegalArgumentException ex) {
            return new Health(HealthState.INCOMPATIBLE, Instant.now(), "PHOTOGRAPHER_PROTOCOL_MISMATCH");
        }
    }

    @Override
    public List<Device> listDevices() {
        var configured = pairing.get();
        if (configured.isEmpty()) return List.of();
        var list = json(configured.get(), "/cameras", null, null, 200).body();
        if (!list.isArray()) throw incompatible();
        return list.valueStream().map(w -> new Device(text(w, "cameraProfileId"), text(w, "displayName"),
                enumValue(w, "state", DeviceState.class), known(w, "capabilities", Capability.class),
                known(w, "supportedFormats", CapturePlan.ImageFormat.class))).toList();
    }

    @Override
    public SessionRef createSession(CapturePlan plan, String key) {
        // Older callers carry one durable operation key, which also identifies this acquisition.
        return createSession(plan, key, key);
    }

    /** Uses the durable local experiment ID for correlation when supplied by a measurement workflow. */
    @Override
    public SessionRef createSession(CapturePlan plan, String externalExperimentId, String key) {
        var conn = requirePair();
        id(key);
        ObjectNode wire = JSON.createObjectNode();
        wire.put("externalExperimentId", id(externalExperimentId)).put("cameraProfileId", plan.deviceId())
                .put("workflowType", plan.workflowType().name());
        var req = plan.requirements();
        var capabilities = conn.descriptor();
        if (!capabilities.supportedFormats().contains(req.preferredImageFormat())
                || (req.lockExposure() && !capabilities.supports(Capability.EXPOSURE_LOCK))
                || (req.lockFocus() && !capabilities.supports(Capability.FOCUS_LOCK))) {
            throw new CaptureProviderException(FailureCode.INVALID_PLAN, false, "UNSUPPORTED_SETTINGS");
        }
        wire.putObject("requestedSettings").put("lockExposure", req.lockExposure()).put("lockFocus", req.lockFocus())
                .put("preferredFormat", req.preferredImageFormat().name());
        var steps = wire.putArray("steps");
        long totalMillis = 0;
        for (var step : plan.steps()) {
            long millis = step.settleDelay().toMillis();
            if (!Duration.ofMillis(millis).equals(step.settleDelay()) || step.triggerMode() != CapturePlan.TriggerMode.EXTERNAL) {
                throw new CaptureProviderException(FailureCode.INVALID_PLAN, false, "UNSUPPORTED_CAPTURE_STEP");
            }
            totalMillis += millis;
            var s = steps.addObject().put("id", step.id()).put("sequenceIndex", step.sequenceIndex())
                    .put("role", step.role().name()).put("triggerMode", step.triggerMode().name()).put("settleDelayMs", millis);
            pattern(s, step.externalPatternId(), step.externalPatternSha256());
        }
        byte[] body = JSON.writeValueAsBytes(wire);
        if (body.length > 131072 || plan.steps().size() > capabilities.limits().maximumSteps()
                || Duration.ofMillis(totalMillis).compareTo(capabilities.limits().maximumSessionDuration()) > 0) {
            throw new CaptureProviderException(FailureCode.INVALID_PLAN, false, "PLAN_LIMIT_EXCEEDED");
        }
        body = journal.pin(conn, List.of("create", key), body, FailureCode.CONFLICT, true);
        var result = json(conn, "/capture-sessions", key, body, 200, 201).body();
        var effective = result.required("effectiveSettings");
        var settings = new CapturePlan.Requirements(effective.required("lockExposure").booleanValue(),
                effective.required("lockFocus").booleanValue(), enumValue(effective, "format", CapturePlan.ImageFormat.class));
        if (!plan.deviceId().equals(text(result, "cameraProfileId")) || !req.equals(settings)) throw incompatible();
        String session = id(text(result, "sessionId"));
        journal.pin(conn, List.of("create-result", key), JSON.writeValueAsBytes(List.of(session, text(result, "createdAt"))),
                FailureCode.INTEGRITY_MISMATCH, false);
        journal.pin(conn, List.of("session", session), body, FailureCode.INTEGRITY_MISMATCH, false);
        return new SessionRef(session, plan, instant(result, "createdAt"));
    }

    private PhotographerPairing.Connection requirePair() {
        return pairing.get().orElseThrow(() -> new CaptureProviderException(
                FailureCode.UNAVAILABLE, false, "PHOTOGRAPHER_NOT_PAIRED"));
    }

    private JsonNode plan(PhotographerPairing.Connection conn, String session) {
        return JSON.readTree(journal.read(conn, List.of("session", id(session))));
    }

    private static String sessionPath(String session) {
        return "/capture-sessions/" + id(session);
    }

    @Override
    public SessionStatus getSession(String session) {
        var conn = requirePair();
        plan(conn, session);
        return status(session, json(conn, sessionPath(session), null, null, 200).body());
    }

    private static SessionStatus status(String session, JsonNode w) {
        if (!session.equals(text(w, "sessionId"))) throw incompatible();
        Failure failure = null;
        if (w.hasNonNull("failure")) {
            var f = w.get("failure");
            failure = new Failure(enumValue(f, "code", FailureCode.class),
                    f.required("retryable").booleanValue(), text(f, "messageCode"));
        }
        return new SessionStatus(session, enumValue(w, "state", SessionState.class),
                w.required("completedSteps").intValue(), w.required("totalSteps").intValue(),
                w.required("lastEventId").longValue(), instant(w, "updatedAt"), failure);
    }

    @Override
    public StepResult triggerStep(String session, String stepId, StepTrigger trigger, String key) {
        id(key);
        var conn = requirePair();
        var plan = plan(conn, session);
        JsonNode step = plan.required("steps").valueStream().filter(s -> stepId.equals(text(s, "id"))).findFirst()
                .orElseThrow(() -> new CaptureProviderException(FailureCode.INVALID_PLAN, false, "UNKNOWN_PLAN_STEP"));
        var payload = JSON.createObjectNode();
        pattern(payload, trigger.externalPatternId(), trigger.externalPatternSha256());
        payload.put("presentedAt", trigger.presentedAt().toString());
        byte[] body = JSON.writeValueAsBytes(payload);
        journal.pin(conn, List.of("trigger-step", session, key), JSON.writeValueAsBytes(stepId), FailureCode.CONFLICT, false);
        body = journal.pin(conn, List.of("trigger-body", session, key), body, FailureCode.CONFLICT, false);
        var response = json(conn, sessionPath(session) + "/steps/" + id(stepId) + "/trigger", key, body, 200, 202);
        var role = enumValue(step, "role", CapturePlan.Role.class);
        if (response.status() == 200) return result(conn, response.body(), session, stepId, role);
        long budget = TimeUnit.SECONDS.toNanos(45);
        long started = System.nanoTime();
        long backoff = 50;
        JsonNode current = response.body();
        while (true) {
            var state = status(session, current);
            for (var entry : current.required("steps")) {
                if (stepId.equals(text(entry, "stepId")) && entry.hasNonNull("result")) {
                    return result(conn, entry.get("result"), session, stepId, role);
                }
            }
            if (state.failure() != null) {
                var failure = state.failure();
                throw new CaptureProviderException(failure.code(), failure.retryable(), failure.messageCode());
            }
            if (state.state().isTerminal() || state.state() == SessionState.RECOVERY_REQUIRED || state.state() == SessionState.PAUSED) {
                throw new CaptureProviderException(FailureCode.CONFLICT, false, state.state() == SessionState.RECOVERY_REQUIRED
                        ? "RECOVERY_REQUIRED" : "STEP_RESULT_UNAVAILABLE");
            }
            if (System.nanoTime() - started >= budget) throw transport();
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw transport();
            }
            backoff = Math.min(1000, backoff * 2);
            current = json(conn, sessionPath(session), null, null, 200).body();
        }
    }

    private StepResult result(PhotographerPairing.Connection conn, JsonNode w, String session, String step, CapturePlan.Role role) {
        AssetMetadata asset = parseAsset(w.required("asset"));
        if (!session.equals(text(w, "sessionId")) || !step.equals(text(w, "stepId")) || !step.equals(asset.stepId())
                || role != asset.role() || !session.equals(asset.sessionId())) throw integrity();
        pinAsset(conn, asset);
        return new StepResult(session, step, asset, instant(w, "completedAt"));
    }

    private static void pattern(ObjectNode node, String pattern, String hash) {
        if (pattern != null) node.put("externalPatternId", pattern).put("externalPatternSha256", hash);
    }

    @Override
    public EventPage events(String session, long after) {
        if (after < 0) throw new IllegalArgumentException("Event cursor cannot be negative");
        var conn = requirePair();
        var plan = plan(conn, session);
        var page = json(conn, sessionPath(session) + "/events?afterEventId=" + after, null, null, 200).body();
        var events = new ArrayList<Event>();
        for (JsonNode w : page.required("events")) {
            var step = w.hasNonNull("stepId") ? text(w, "stepId") : null;
            AssetMetadata asset = null;
            if (w.hasNonNull("asset")) {
                asset = parseAsset(w.get("asset"));
                verifyPlanAsset(plan, session, asset);
                if (!asset.stepId().equals(step)) throw integrity();
                pinAsset(conn, asset);
            }
            if (!session.equals(text(w, "sessionId")) || w.required("eventId").longValue() <= after) throw incompatible();
            events.add(new Event(w.get("eventId").longValue(), session, step, enumValue(w, "state", SessionState.class),
                    text(w, "messageCode"), instant(w, "timestamp"), asset));
        }
        boolean hasMore = page.required("hasMore").booleanValue();
        long cursor = events.isEmpty() ? after : events.getLast().eventId();
        if ((hasMore && events.isEmpty()) || page.required("lastEventId").longValue() < cursor) throw incompatible();
        return new EventPage(events, cursor, hasMore);
    }

    @Override
    public AssetMetadata getAssetMetadata(String session, String assetId) {
        return metadata(requirePair(), session, assetId);
    }

    private AssetMetadata metadata(PhotographerPairing.Connection conn, String session, String assetId) {
        var plan = plan(conn, session);
        var w = json(conn, assetPath(session, assetId) + "/metadata", null, null, 200).body();
        var asset = parseAsset(w);
        if (!assetId.equals(asset.assetId())) throw integrity();
        verifyPlanAsset(plan, session, asset);
        pinAsset(conn, asset);
        return asset;
    }

    private static void verifyPlanAsset(JsonNode plan, String session, AssetMetadata asset) {
        boolean found = plan.required("steps").valueStream().anyMatch(s -> asset.stepId().equals(text(s, "id"))
                && asset.role().name().equals(text(s, "role")));
        if (!session.equals(asset.sessionId()) || !found) throw integrity();
    }

    private void pinAsset(PhotographerPairing.Connection conn, AssetMetadata asset) {
        if (asset.byteLength() > conn.descriptor().limits().maximumAssetBytes()) throw integrity();
        journal.pin(conn, List.of("asset", asset.sessionId(), asset.assetId()), JSON.writeValueAsBytes(asset),
                FailureCode.INTEGRITY_MISMATCH, false);
    }

    private static AssetMetadata parseAsset(JsonNode w) {
        return new AssetMetadata(text(w, "sessionId"), text(w, "assetId"), text(w, "stepId"),
                enumValue(w, "role", CapturePlan.Role.class), text(w, "originalFileName"), text(w, "mediaType"),
                w.required("byteLength").longValue(), text(w, "sha256"), instant(w, "capturedAt"));
    }

    private static String assetPath(String session, String asset) {
        return sessionPath(session) + "/assets/" + id(asset);
    }

    @Override
    public InputStream openAsset(String session, String assetId, AssetRead read) {
        var conn = requirePair();
        var asset = metadata(conn, session, assetId);
        return PhotographerMedia.open(conn, assetPath(session, assetId), asset, read);
    }

    @Override
    public void cancel(String session, String key) {
        var conn = requirePair();
        plan(conn, session);
        journal.pin(conn, List.of("cancel", session, id(key)), new byte[0], FailureCode.CONFLICT, false);
        json(conn, sessionPath(session) + "/cancel", key, null, 204);
    }

    private static String text(JsonNode wire, String key) {
        var n = wire.path(key);
        if (!n.isTextual()) throw incompatible();
        return n.asText();
    }

    private static Instant instant(JsonNode wire, String key) {
        try {
            return Instant.parse(text(wire, key));
        } catch (java.time.format.DateTimeParseException ex) {
            throw incompatible();
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonNode wire, String key, Class<E> type) {
        try {
            return Enum.valueOf(type, text(wire, key));
        } catch (IllegalArgumentException ex) {
            throw incompatible();
        }
    }

    private static <E extends Enum<E>> Set<E> known(JsonNode wire, String field, Class<E> type) {
        EnumSet<E> found = EnumSet.noneOf(type);
        var list = wire.required(field);
        if (!list.isArray()) throw incompatible();
        for (E entry : type.getEnumConstants()) {
            if (list.valueStream().anyMatch(v -> entry.name().equals(v.asText()))) found.add(entry);
        }
        return found;
    }
}

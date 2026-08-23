package org.fresnel.backend.measurement;

import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Deterministic development/test provider used to exercise the complete capture
 * contract without a real camera or network service.
 *
 * <p>The provider is disabled by default and enabled only with
 * {@code fresnel.capture.mock.enabled=true}. It implements persistent-within-the-
 * process idempotency for session creation, step triggering and cancellation.
 * A retry after an uncertain response therefore returns the original capture
 * instead of generating a second image.</p>
 */
@Component
@ConditionalOnProperty(prefix = "fresnel.capture.mock", name = "enabled", havingValue = "true")
public final class MockCaptureProvider implements CaptureProvider {

    public static final String PROVIDER_ID = "mock-capture";
    public static final String DEVICE_ID = "mock-camera";

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.STILL_CAPTURE,
            Capability.EXTERNAL_STEP_TRIGGER,
            Capability.EXPOSURE_LOCK,
            Capability.FOCUS_LOCK,
            Capability.DURABLE_MEDIA,
            Capability.RANGE_DOWNLOAD,
            Capability.SHA256_INTEGRITY,
            Capability.SSE_EVENTS);

    private static final Descriptor DESCRIPTOR = new Descriptor(
            PROVIDER_ID,
            "Mock camera service",
            1,
            1,
            CAPABILITIES,
            Set.of(CapturePlan.ImageFormat.PNG),
            new Limits(64, 4 * 1024 * 1024L, Duration.ofMinutes(15)));

    private static final Device DEVICE = new Device(
            DEVICE_ID,
            "Deterministic mock camera",
            DeviceState.CONNECTED,
            CAPABILITIES,
            Set.of(CapturePlan.ImageFormat.PNG));

    private final Clock clock;
    private final AtomicLong nextSessionNumber = new AtomicLong();
    private final Map<String, MockSession> sessions = new HashMap<>();
    private final Map<String, CreationRecord> creationsByKey = new HashMap<>();

    public MockCaptureProvider() {
        this(Clock.systemUTC());
    }

    MockCaptureProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Health health() {
        return new Health(HealthState.CONNECTED, clock.instant(), "MOCK_CONNECTED");
    }

    @Override
    public List<Device> listDevices() {
        return List.of(DEVICE);
    }

    @Override
    public synchronized SessionRef createSession(CapturePlan plan, String idempotencyKey) {
        Objects.requireNonNull(plan, "plan");
        String key = requireIdempotencyKey(idempotencyKey);
        CreationRecord existing = creationsByKey.get(key);
        if (existing != null) {
            if (!existing.plan().equals(plan)) {
                throw failure(FailureCode.CONFLICT, false, "IDEMPOTENCY_KEY_REUSED");
            }
            return existing.sessionRef();
        }

        validatePlan(plan);
        String id = "mock-session-" + nextSessionNumber.incrementAndGet();
        Instant now = clock.instant();
        SessionRef ref = new SessionRef(id, plan, now);
        MockSession session = new MockSession(id, plan, now);
        session.state = SessionState.READY;
        session.updatedAt = now;
        session.addEvent(null, SessionState.READY, "MOCK_SESSION_READY", null, now);
        sessions.put(id, session);
        creationsByKey.put(key, new CreationRecord(plan, ref));
        return ref;
    }

    @Override
    public synchronized SessionStatus getSession(String remoteSessionId) {
        return requireSession(remoteSessionId).status();
    }

    @Override
    public synchronized StepResult triggerStep(
            String remoteSessionId,
            String stepId,
            StepTrigger trigger,
            String idempotencyKey) {
        MockSession session = requireSession(remoteSessionId);
        Objects.requireNonNull(trigger, "trigger");
        String normalizedStepId = requireIdentifier(stepId, "stepId");
        String key = requireIdempotencyKey(idempotencyKey);

        TriggerRecord previous = session.triggerByKey.get(key);
        if (previous != null) {
            if (!previous.stepId().equals(normalizedStepId)
                    || !previous.trigger().equals(trigger)) {
                throw failure(FailureCode.CONFLICT, false, "IDEMPOTENCY_KEY_REUSED");
            }
            return previous.result();
        }

        if (session.state.isTerminal()) {
            throw failure(FailureCode.CONFLICT, false, "SESSION_TERMINAL");
        }
        if (session.completedSteps >= session.plan.steps().size()) {
            throw failure(FailureCode.CONFLICT, false, "NO_CAPTURE_STEP_PENDING");
        }

        CapturePlan.Step expected = session.plan.steps().get(session.completedSteps);
        if (!expected.id().equals(normalizedStepId)) {
            throw failure(FailureCode.CONFLICT, false, "CAPTURE_STEP_OUT_OF_ORDER");
        }
        if (!Objects.equals(expected.externalPatternId(), trigger.externalPatternId())
                || !Objects.equals(
                        expected.externalPatternSha256(), trigger.externalPatternSha256())) {
            throw failure(FailureCode.INVALID_PLAN, false, "DISPLAYED_PATTERN_MISMATCH");
        }

        Instant startedAt = clock.instant();
        session.state = SessionState.CAPTURING;
        session.updatedAt = startedAt;
        session.addEvent(expected.id(), SessionState.CAPTURING,
                "MOCK_CAPTURE_STARTED", null, startedAt);

        byte[] png = renderPng(session.id, expected);
        String assetId = "asset-" + expected.sequenceIndex();
        Instant completedAt = clock.instant();
        AssetMetadata metadata = new AssetMetadata(
                session.id,
                assetId,
                expected.id(),
                expected.role(),
                "mock-" + expected.id() + ".png",
                "image/png",
                png.length,
                sha256(png),
                completedAt);
        session.assets.put(assetId, new StoredAsset(metadata, png));
        session.completedSteps += 1;
        session.state = session.completedSteps == session.plan.steps().size()
                ? SessionState.COMPLETED
                : SessionState.WAITING_FOR_EXTERNAL_TRIGGER;
        session.updatedAt = completedAt;
        session.addEvent(expected.id(), session.state,
                session.state == SessionState.COMPLETED
                        ? "MOCK_SESSION_COMPLETED"
                        : "MOCK_STEP_COMPLETED",
                metadata,
                completedAt);

        StepResult result = new StepResult(session.id, expected.id(), metadata, completedAt);
        session.triggerByKey.put(key, new TriggerRecord(expected.id(), trigger, result));
        return result;
    }

    @Override
    public synchronized EventPage events(String remoteSessionId, long afterEventId) {
        if (afterEventId < 0) {
            throw new IllegalArgumentException("afterEventId must not be negative");
        }
        MockSession session = requireSession(remoteSessionId);
        List<Event> events = session.events.stream()
                .filter(event -> event.eventId() > afterEventId)
                .limit(1_000)
                .toList();
        long lastEventId = session.events.isEmpty()
                ? 0
                : session.events.getLast().eventId();
        boolean hasMore = session.events.stream()
                .filter(event -> event.eventId() > afterEventId)
                .count() > events.size();
        return new EventPage(events, lastEventId, hasMore);
    }

    @Override
    public synchronized AssetMetadata getAssetMetadata(
            String remoteSessionId, String assetId) {
        return requireAsset(requireSession(remoteSessionId), assetId).metadata();
    }

    @Override
    public synchronized InputStream openAsset(
            String remoteSessionId,
            String assetId,
            AssetRead read) {
        StoredAsset asset = requireAsset(requireSession(remoteSessionId), assetId);
        Objects.requireNonNull(read, "read");
        byte[] bytes = asset.bytes();
        if (read instanceof FullAsset) {
            return new ByteArrayInputStream(bytes.clone());
        }
        ByteRange range = (ByteRange) read;
        if (range.offset() > bytes.length || range.endExclusive() > bytes.length) {
            throw failure(FailureCode.INVALID_PLAN, false, "ASSET_RANGE_NOT_SATISFIABLE");
        }
        return new ByteArrayInputStream(
                bytes,
                Math.toIntExact(range.offset()),
                Math.toIntExact(range.length()));
    }

    @Override
    public synchronized void cancel(String remoteSessionId, String idempotencyKey) {
        MockSession session = requireSession(remoteSessionId);
        String key = requireIdempotencyKey(idempotencyKey);
        if (!session.cancelKeys.add(key)) {
            return;
        }
        if (session.state.isTerminal()) {
            return;
        }
        Instant now = clock.instant();
        session.state = SessionState.CANCELLED;
        session.updatedAt = now;
        session.addEvent(null, SessionState.CANCELLED, "MOCK_SESSION_CANCELLED", null, now);
    }

    private static void validatePlan(CapturePlan plan) {
        if (!DEVICE_ID.equals(plan.deviceId())) {
            throw failure(FailureCode.DEVICE_UNAVAILABLE, false, "MOCK_DEVICE_NOT_FOUND");
        }
        if (plan.steps().size() > DESCRIPTOR.limits().maximumSteps()) {
            throw failure(FailureCode.INVALID_PLAN, false, "TOO_MANY_CAPTURE_STEPS");
        }
        if (!DESCRIPTOR.supportedFormats().contains(
                plan.requirements().preferredImageFormat())) {
            throw failure(FailureCode.INVALID_PLAN, false, "IMAGE_FORMAT_UNSUPPORTED");
        }
        if (plan.requirements().lockExposure()
                && !DESCRIPTOR.supports(Capability.EXPOSURE_LOCK)) {
            throw failure(FailureCode.INVALID_PLAN, false, "EXPOSURE_LOCK_UNSUPPORTED");
        }
        if (plan.requirements().lockFocus()
                && !DESCRIPTOR.supports(Capability.FOCUS_LOCK)) {
            throw failure(FailureCode.INVALID_PLAN, false, "FOCUS_LOCK_UNSUPPORTED");
        }
    }

    private MockSession requireSession(String id) {
        String normalized = requireIdentifier(id, "remoteSessionId");
        MockSession session = sessions.get(normalized);
        if (session == null) {
            throw failure(FailureCode.SESSION_NOT_FOUND, false, "SESSION_NOT_FOUND");
        }
        return session;
    }

    private static StoredAsset requireAsset(MockSession session, String id) {
        String normalized = requireIdentifier(id, "assetId");
        StoredAsset asset = session.assets.get(normalized);
        if (asset == null) {
            throw failure(FailureCode.ASSET_NOT_FOUND, false, "ASSET_NOT_FOUND");
        }
        return asset;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        return value.trim();
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !CaptureProvider.IDENTIFIER.matcher(value.trim()).matches()
                || value.trim().length() > 128) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static CaptureProviderException failure(
            FailureCode code, boolean retryable, String messageCode) {
        return new CaptureProviderException(code, retryable, messageCode);
    }

    private static byte[] renderPng(String sessionId, CapturePlan.Step step) {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_BYTE_GRAY);
        int seed = Objects.hash(sessionId, step.id(), step.sequenceIndex(), step.role());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = Math.floorMod(seed + x * 31 + y * 17 + x * y, 256);
                image.getRaster().setSample(x, y, 0, value);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot generate deterministic mock capture", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record CreationRecord(CapturePlan plan, SessionRef sessionRef) {}

    private record TriggerRecord(String stepId, StepTrigger trigger, StepResult result) {}

    private record StoredAsset(AssetMetadata metadata, byte[] bytes) {
        private StoredAsset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class MockSession {
        private final String id;
        private final CapturePlan plan;
        private final Instant createdAt;
        private final List<Event> events = new ArrayList<>();
        private final Map<String, TriggerRecord> triggerByKey = new HashMap<>();
        private final Map<String, StoredAsset> assets = new HashMap<>();
        private final Set<String> cancelKeys = new HashSet<>();
        private SessionState state = SessionState.CREATED;
        private int completedSteps;
        private Instant updatedAt;

        private MockSession(String id, CapturePlan plan, Instant createdAt) {
            this.id = id;
            this.plan = plan;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private void addEvent(
                String stepId,
                SessionState eventState,
                String messageCode,
                AssetMetadata asset,
                Instant timestamp) {
            events.add(new Event(
                    events.size() + 1L,
                    id,
                    stepId,
                    eventState,
                    messageCode,
                    timestamp,
                    asset));
        }

        private SessionStatus status() {
            return new SessionStatus(
                    id,
                    state,
                    completedSteps,
                    plan.steps().size(),
                    events.isEmpty() ? 0 : events.getLast().eventId(),
                    updatedAt,
                    null);
        }
    }
}

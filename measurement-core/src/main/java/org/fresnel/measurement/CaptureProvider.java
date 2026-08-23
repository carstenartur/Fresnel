package org.fresnel.measurement;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service boundary through which Fresnel obtains camera captures.
 *
 * <p>Implementations may use manual upload, an in-process mock or the versioned
 * Photographer REST bridge. Optical measurement algorithms depend only on this
 * contract and never on camera-vendor or transport-specific classes.</p>
 */
public interface CaptureProvider {

    Descriptor descriptor();

    Health health();

    List<Device> listDevices();

    SessionRef createSession(CapturePlan plan, String idempotencyKey);

    SessionStatus getSession(String remoteSessionId);

    EventPage events(String remoteSessionId, long afterEventId);

    AssetMetadata getAssetMetadata(String remoteSessionId, String assetId);

    InputStream openAsset(String remoteSessionId, String assetId, AssetRead read);

    void cancel(String remoteSessionId, String idempotencyKey);

    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    Pattern MEDIA_TYPE = Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

    private static String requireIdentifier(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must be single-line");
        }
        return normalized;
    }

    private static Set<Capability> copyCapabilities(Set<Capability> capabilities) {
        return capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    private static Set<CapturePlan.ImageFormat> copyFormats(
            Set<CapturePlan.ImageFormat> formats) {
        return formats == null ? Set.of() : Set.copyOf(formats);
    }

    enum Capability {
        STILL_CAPTURE,
        LIVE_VIEW_SEQUENCE,
        EXTERNAL_STEP_TRIGGER,
        EXPOSURE_LOCK,
        FOCUS_LOCK,
        DURABLE_MEDIA,
        RANGE_DOWNLOAD,
        SHA256_INTEGRITY,
        SSE_EVENTS
    }

    enum HealthState {
        CONNECTED,
        DEGRADED,
        DISCONNECTED,
        INCOMPATIBLE,
        AUTHENTICATION_FAILED,
        REVOKED
    }

    enum DeviceState {
        CONNECTED,
        BUSY,
        DISCONNECTED,
        UNSUPPORTED
    }

    enum SessionState {
        CREATED,
        READY,
        WAITING_FOR_EXTERNAL_TRIGGER,
        CAPTURING,
        PERSISTING,
        TRANSFERRING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED,
        RECOVERY_REQUIRED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    enum FailureCode {
        UNAVAILABLE,
        AUTHENTICATION_FAILED,
        INCOMPATIBLE,
        INVALID_PLAN,
        DEVICE_UNAVAILABLE,
        SESSION_NOT_FOUND,
        ASSET_NOT_FOUND,
        CONFLICT,
        TRANSIENT_IO,
        INTEGRITY_MISMATCH,
        INTERNAL_ERROR
    }

    /** Immutable provider metadata used for negotiation before camera contact. */
    record Descriptor(
            String id,
            String displayName,
            int protocolVersion,
            int minimumSupportedProtocolVersion,
            Set<Capability> capabilities,
            Set<CapturePlan.ImageFormat> supportedFormats,
            Limits limits) {

        public Descriptor {
            id = requireIdentifier(id, "provider id", 64);
            displayName = requireText(displayName, "displayName", 128);
            if (protocolVersion < 1) {
                throw new IllegalArgumentException("protocolVersion must be positive");
            }
            if (minimumSupportedProtocolVersion < 1
                    || minimumSupportedProtocolVersion > protocolVersion) {
                throw new IllegalArgumentException(
                        "minimumSupportedProtocolVersion must be within the provider range");
            }
            capabilities = copyCapabilities(capabilities);
            supportedFormats = copyFormats(supportedFormats);
            Objects.requireNonNull(limits, "limits");
        }

        public boolean supports(Capability capability) {
            return capabilities.contains(Objects.requireNonNull(capability, "capability"));
        }
    }

    /** Hard provider limits published before a capture session is created. */
    record Limits(int maximumSteps, long maximumAssetBytes, Duration maximumSessionDuration) {
        public Limits {
            if (maximumSteps < 1 || maximumSteps > CapturePlan.MAX_STEPS) {
                throw new IllegalArgumentException(
                        "maximumSteps must be between 1 and " + CapturePlan.MAX_STEPS);
            }
            if (maximumAssetBytes < 1) {
                throw new IllegalArgumentException("maximumAssetBytes must be positive");
            }
            Objects.requireNonNull(maximumSessionDuration, "maximumSessionDuration");
            if (maximumSessionDuration.isZero() || maximumSessionDuration.isNegative()) {
                throw new IllegalArgumentException("maximumSessionDuration must be positive");
            }
        }
    }

    /** Last observed connection state. */
    record Health(HealthState state, Instant checkedAt, String messageCode) {
        public Health {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(checkedAt, "checkedAt");
            messageCode = requireIdentifier(messageCode, "messageCode", 96)
                    .toUpperCase(Locale.ROOT);
        }

        public boolean usable() {
            return state == HealthState.CONNECTED || state == HealthState.DEGRADED;
        }
    }

    /** Redacted device description suitable for selection in Fresnel. */
    record Device(
            String id,
            String displayName,
            DeviceState state,
            Set<Capability> capabilities,
            Set<CapturePlan.ImageFormat> supportedFormats) {

        public Device {
            id = requireIdentifier(id, "device id", 128);
            displayName = requireText(displayName, "displayName", 128);
            Objects.requireNonNull(state, "state");
            capabilities = copyCapabilities(capabilities);
            supportedFormats = copyFormats(supportedFormats);
        }

        public boolean supports(Capability capability) {
            return capabilities.contains(Objects.requireNonNull(capability, "capability"));
        }
    }

    /** Durable remote identity returned after idempotent session creation. */
    record SessionRef(String id, CapturePlan effectivePlan, Instant createdAt) {
        public SessionRef {
            id = requireIdentifier(id, "session id", 128);
            Objects.requireNonNull(effectivePlan, "effectivePlan");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Sanitized failure attached to a provider session or event. */
    record Failure(FailureCode code, boolean retryable, String messageCode) {
        public Failure {
            Objects.requireNonNull(code, "code");
            messageCode = requireIdentifier(messageCode, "messageCode", 96)
                    .toUpperCase(Locale.ROOT);
        }
    }

    /** Polling representation of the current remote session state. */
    record SessionStatus(
            String id,
            SessionState state,
            int completedSteps,
            int totalSteps,
            long lastEventId,
            Instant updatedAt,
            Failure failure) {

        public SessionStatus {
            id = requireIdentifier(id, "session id", 128);
            Objects.requireNonNull(state, "state");
            if (totalSteps < 1 || totalSteps > CapturePlan.MAX_STEPS) {
                throw new IllegalArgumentException("totalSteps is outside the supported range");
            }
            if (completedSteps < 0 || completedSteps > totalSteps) {
                throw new IllegalArgumentException("completedSteps must be within totalSteps");
            }
            if (lastEventId < 0) {
                throw new IllegalArgumentException("lastEventId must not be negative");
            }
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (state == SessionState.FAILED && failure == null) {
                throw new IllegalArgumentException("failed sessions require a failure classification");
            }
            if (state != SessionState.FAILED && failure != null) {
                throw new IllegalArgumentException("only failed sessions may carry a failure");
            }
        }

        public double progress() {
            return (double) completedSteps / totalSteps;
        }
    }

    /** One durable, monotonically ordered provider event. */
    record Event(
            long eventId,
            String sessionId,
            String stepId,
            SessionState state,
            String messageCode,
            Instant timestamp,
            AssetMetadata asset) {

        public Event {
            if (eventId < 1) {
                throw new IllegalArgumentException("eventId must be positive");
            }
            sessionId = requireIdentifier(sessionId, "session id", 128);
            if (stepId != null) {
                stepId = requireIdentifier(stepId, "step id", 64);
            }
            Objects.requireNonNull(state, "state");
            messageCode = requireIdentifier(messageCode, "messageCode", 96)
                    .toUpperCase(Locale.ROOT);
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    /** Bounded page used by SSE-recovery and polling fallbacks. */
    record EventPage(List<Event> events, long lastEventId, boolean hasMore) {
        public EventPage {
            Objects.requireNonNull(events, "events");
            events = List.copyOf(events);
            if (events.size() > 1_000) {
                throw new IllegalArgumentException("event page exceeds 1000 events");
            }
            if (lastEventId < 0) {
                throw new IllegalArgumentException("lastEventId must not be negative");
            }
            long previous = 0;
            for (Event event : events) {
                Objects.requireNonNull(event, "event");
                if (event.eventId() <= previous) {
                    throw new IllegalArgumentException("event ids must be strictly increasing");
                }
                previous = event.eventId();
            }
            if (!events.isEmpty() && lastEventId < events.getLast().eventId()) {
                throw new IllegalArgumentException("lastEventId precedes the returned event page");
            }
        }
    }

    /** Integrity metadata committed before an asset is announced as available. */
    record AssetMetadata(
            String sessionId,
            String assetId,
            String stepId,
            CapturePlan.Role role,
            String originalFileName,
            String mediaType,
            long byteLength,
            String sha256,
            Instant capturedAt) {

        public AssetMetadata {
            sessionId = requireIdentifier(sessionId, "session id", 128);
            assetId = requireIdentifier(assetId, "asset id", 128);
            stepId = requireIdentifier(stepId, "step id", 64);
            Objects.requireNonNull(role, "role");
            originalFileName = requireText(originalFileName, "originalFileName", 255);
            if (originalFileName.contains("/") || originalFileName.contains("\\")
                    || originalFileName.equals(".") || originalFileName.equals("..")) {
                throw new IllegalArgumentException("originalFileName must be a portable basename");
            }
            mediaType = requireText(mediaType, "mediaType", 128).toLowerCase(Locale.ROOT);
            if (!MEDIA_TYPE.matcher(mediaType).matches()) {
                throw new IllegalArgumentException("mediaType is invalid");
            }
            if (byteLength < 1) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
            }
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }

    /** Selection used when opening an asset stream. */
    sealed interface AssetRead permits FullAsset, ByteRange {}

    /** Requests the complete asset. */
    enum FullAsset implements AssetRead {
        INSTANCE
    }

    /** Requests a bounded byte range for resumable transfer. */
    record ByteRange(long offset, long length) implements AssetRead {
        public ByteRange {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            if (length < 1) {
                throw new IllegalArgumentException("length must be positive");
            }
            if (offset > Long.MAX_VALUE - length) {
                throw new IllegalArgumentException("byte range overflows long");
            }
        }

        public long endExclusive() {
            return offset + length;
        }
    }
}

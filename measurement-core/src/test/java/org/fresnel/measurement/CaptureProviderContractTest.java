package org.fresnel.measurement;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureProviderContractTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void descriptorAndDevicePublishImmutableNegotiationData() {
        Set<CaptureProvider.Capability> capabilities = new java.util.HashSet<>(Set.of(
                CaptureProvider.Capability.STILL_CAPTURE,
                CaptureProvider.Capability.SHA256_INTEGRITY));
        CaptureProvider.Descriptor descriptor = new CaptureProvider.Descriptor(
                " photographer-rest ",
                " Photographer ",
                1,
                1,
                capabilities,
                Set.of(CapturePlan.ImageFormat.JPEG, CapturePlan.ImageFormat.RAW_WITH_PREVIEW),
                new CaptureProvider.Limits(64, 2_000_000_000L, Duration.ofMinutes(30)));
        capabilities.clear();

        assertEquals("photographer-rest", descriptor.id());
        assertEquals("Photographer", descriptor.displayName());
        assertTrue(descriptor.supports(CaptureProvider.Capability.STILL_CAPTURE));
        assertFalse(descriptor.capabilities().isEmpty());
        assertThrows(UnsupportedOperationException.class, descriptor.capabilities()::clear);

        CaptureProvider.Device device = new CaptureProvider.Device(
                "camera:1", "Canon", CaptureProvider.DeviceState.CONNECTED,
                descriptor.capabilities(), descriptor.supportedFormats());
        assertTrue(device.supports(CaptureProvider.Capability.SHA256_INTEGRITY));
        assertThrows(UnsupportedOperationException.class, device.supportedFormats()::clear);
    }

    @Test
    void descriptorAndLimitsRejectInvalidProtocolAndBounds() {
        CaptureProvider.Limits limits =
                new CaptureProvider.Limits(1, 1, Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.Descriptor(
                "p", "P", 0, 1, Set.of(), Set.of(), limits));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.Descriptor(
                "p", "P", 1, 2, Set.of(), Set.of(), limits));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.Limits(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.Limits(CapturePlan.MAX_STEPS + 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.Limits(1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.Limits(1, 1, Duration.ZERO));
    }

    @Test
    void healthUsesTruthfulUsabilityAndStableMessageCodes() {
        CaptureProvider.Health connected = new CaptureProvider.Health(
                CaptureProvider.HealthState.CONNECTED, NOW, " ok ");
        CaptureProvider.Health degraded = new CaptureProvider.Health(
                CaptureProvider.HealthState.DEGRADED, NOW, "slow");
        CaptureProvider.Health disconnected = new CaptureProvider.Health(
                CaptureProvider.HealthState.DISCONNECTED, NOW, "offline");

        assertEquals("OK", connected.messageCode());
        assertTrue(connected.usable());
        assertTrue(degraded.usable());
        assertFalse(disconnected.usable());
    }

    @Test
    void sessionStatusValidatesProgressAndFailureSemantics() {
        CaptureProvider.SessionStatus running = new CaptureProvider.SessionStatus(
                "session:1", CaptureProvider.SessionState.CAPTURING,
                2, 4, 7, NOW, null);
        assertEquals(0.5, running.progress());
        assertFalse(running.state().isTerminal());

        CaptureProvider.Failure failure = new CaptureProvider.Failure(
                CaptureProvider.FailureCode.TRANSIENT_IO, true, "network_timeout");
        CaptureProvider.SessionStatus failed = new CaptureProvider.SessionStatus(
                "session:1", CaptureProvider.SessionState.FAILED,
                2, 4, 8, NOW, failure);
        assertTrue(failed.state().isTerminal());
        assertEquals("NETWORK_TIMEOUT", failed.failure().messageCode());

        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.SessionStatus(
                "s", CaptureProvider.SessionState.FAILED, 0, 1, 0, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.SessionStatus(
                "s", CaptureProvider.SessionState.READY, 0, 1, 0, NOW, failure));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.SessionStatus(
                "s", CaptureProvider.SessionState.READY, 2, 1, 0, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.SessionStatus(
                "s", CaptureProvider.SessionState.READY, 0, 1, -1, NOW, null));
    }

    @Test
    void eventsAreOrderedBoundedAndImmutable() {
        CaptureProvider.Event first = new CaptureProvider.Event(
                1, "session:1", null, CaptureProvider.SessionState.READY,
                "ready", NOW, null);
        CaptureProvider.Event second = new CaptureProvider.Event(
                2, "session:1", "step-1", CaptureProvider.SessionState.PERSISTING,
                "persisting", NOW.plusSeconds(1), asset());
        List<CaptureProvider.Event> mutable = new ArrayList<>(List.of(first, second));
        CaptureProvider.EventPage page = new CaptureProvider.EventPage(mutable, 2, false);
        mutable.clear();

        assertEquals(2, page.events().size());
        assertEquals("PERSISTING", page.events().getLast().messageCode());
        assertThrows(UnsupportedOperationException.class, page.events()::clear);
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.EventPage(List.of(second, first), 2, false));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.EventPage(List.of(second), 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.Event(0, "s", null,
                        CaptureProvider.SessionState.READY, "ready", NOW, null));
    }

    @Test
    void assetMetadataCarriesPortableIntegrityDataOnly() {
        CaptureProvider.AssetMetadata asset = asset();
        assertEquals("image/jpeg", asset.mediaType());
        assertEquals(HASH, asset.sha256());

        assertThrows(IllegalArgumentException.class, () -> asset("../private.jpg", "image/jpeg", HASH, 10));
        assertThrows(IllegalArgumentException.class, () -> asset("private.jpg", "bad type", HASH, 10));
        assertThrows(IllegalArgumentException.class, () -> asset("private.jpg", "image/jpeg", "ABC", 10));
        assertThrows(IllegalArgumentException.class, () -> asset("private.jpg", "image/jpeg", HASH, 0));
    }

    @Test
    void byteRangesAreBoundedAndDetectOverflow() {
        CaptureProvider.ByteRange range = new CaptureProvider.ByteRange(10, 20);
        assertEquals(30, range.endExclusive());
        assertSame(CaptureProvider.FullAsset.INSTANCE, CaptureProvider.FullAsset.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.ByteRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureProvider.ByteRange(0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new CaptureProvider.ByteRange(Long.MAX_VALUE - 1, 2));
    }

    @Test
    void providerExceptionKeepsOnlyTypedSanitizedFailure() {
        IllegalStateException cause = new IllegalStateException("private provider detail");
        CaptureProviderException exception = new CaptureProviderException(
                CaptureProvider.FailureCode.UNAVAILABLE, true, " provider:offline ", cause);

        assertEquals(CaptureProvider.FailureCode.UNAVAILABLE, exception.code());
        assertTrue(exception.retryable());
        assertEquals("PROVIDER:OFFLINE", exception.messageCode());
        assertEquals("PROVIDER:OFFLINE", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertEquals(exception.code(), exception.toFailure().code());
        assertThrows(IllegalArgumentException.class, () -> new CaptureProviderException(
                CaptureProvider.FailureCode.INTERNAL_ERROR, false, "bad message code"));
    }

    @Test
    void sessionReferenceAndMeasurementStateExposeDurableLifecycle() {
        CapturePlan plan = bosPlan();
        CaptureProvider.SessionRef ref =
                new CaptureProvider.SessionRef("remote:1", plan, NOW);
        assertEquals(plan, ref.effectivePlan());
        assertFalse(MeasurementSessionState.CAPTURING.isTerminal());
        assertTrue(MeasurementSessionState.COMPLETED.isTerminal());
        assertTrue(MeasurementSessionState.FAILED.isTerminal());
        assertTrue(MeasurementSessionState.CANCELLED.isTerminal());
    }

    private static CaptureProvider.AssetMetadata asset() {
        return asset("capture.jpg", "IMAGE/JPEG", HASH, 1234);
    }

    private static CaptureProvider.AssetMetadata asset(
            String fileName, String mediaType, String hash, long length) {
        return new CaptureProvider.AssetMetadata(
                "session:1", "asset:1", "step-1", CapturePlan.Role.REFERENCE,
                fileName, mediaType, length, hash, NOW);
    }

    private static CapturePlan bosPlan() {
        return new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                "camera:1",
                new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.JPEG),
                List.of(
                        new CapturePlan.Step("reference", 0, CapturePlan.Role.REFERENCE,
                                CapturePlan.TriggerMode.EXTERNAL, Duration.ZERO, null),
                        new CapturePlan.Step("disturbed", 1, CapturePlan.Role.DISTURBED,
                                CapturePlan.TriggerMode.EXTERNAL, Duration.ZERO, null)));
    }
}

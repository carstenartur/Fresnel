package org.fresnel.backend.measurement;

import org.fresnel.measurement.CapturePlan;
import org.fresnel.measurement.CaptureProvider;
import org.fresnel.measurement.CaptureProviderException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCaptureProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-23T18:30:00Z");
    private static final String PATTERN_HASH = "b".repeat(64);

    private final MockCaptureProvider provider = new MockCaptureProvider(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void descriptorHealthAndDeviceAreRedactedAndUsable() {
        assertEquals(MockCaptureProvider.PROVIDER_ID, provider.descriptor().id());
        assertTrue(provider.descriptor().supports(CaptureProvider.Capability.STILL_CAPTURE));
        assertTrue(provider.descriptor().supports(CaptureProvider.Capability.EXTERNAL_STEP_TRIGGER));
        assertEquals(CaptureProvider.HealthState.CONNECTED, provider.health().state());
        assertEquals(NOW, provider.health().checkedAt());
        assertEquals(1, provider.listDevices().size());
        assertEquals(MockCaptureProvider.DEVICE_ID, provider.listDevices().getFirst().id());
    }

    @Test
    void createSessionIsIdempotentAndRejectsKeyReuseWithAnotherPlan() {
        CapturePlan plan = bosPlan();
        CaptureProvider.SessionRef first = provider.createSession(plan, "create:1");
        CaptureProvider.SessionRef replay = provider.createSession(plan, "create:1");

        assertSame(first, replay);
        assertEquals("mock-session-1", first.id());
        assertEquals(CaptureProvider.SessionState.READY,
                provider.getSession(first.id()).state());

        CapturePlan changed = new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                MockCaptureProvider.DEVICE_ID,
                new CapturePlan.Requirements(false, true, CapturePlan.ImageFormat.PNG),
                plan.steps());
        CaptureProviderException conflict = assertThrows(CaptureProviderException.class,
                () -> provider.createSession(changed, "create:1"));
        assertEquals(CaptureProvider.FailureCode.CONFLICT, conflict.code());
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.messageCode());
    }

    @Test
    void triggerCapturesExactlyOnceAndCompletesBosSequenceInOrder() throws IOException {
        CaptureProvider.SessionRef session = provider.createSession(bosPlan(), "create:1");
        CaptureProvider.StepTrigger trigger =
                new CaptureProvider.StepTrigger(null, null, NOW);

        CaptureProvider.StepResult reference = provider.triggerStep(
                session.id(), "reference", trigger, "trigger:reference");
        CaptureProvider.StepResult replay = provider.triggerStep(
                session.id(), "reference", trigger, "trigger:reference");
        assertSame(reference, replay);
        assertEquals(CaptureProvider.SessionState.WAITING_FOR_EXTERNAL_TRIGGER,
                provider.getSession(session.id()).state());
        assertEquals(1, provider.getSession(session.id()).completedSteps());

        CaptureProvider.AssetMetadata metadata = provider.getAssetMetadata(
                session.id(), reference.asset().assetId());
        assertEquals("image/png", metadata.mediaType());
        assertEquals(64, metadata.sha256().length());

        byte[] full = provider.openAsset(
                session.id(), metadata.assetId(), CaptureProvider.FullAsset.INSTANCE).readAllBytes();
        byte[] range = provider.openAsset(
                session.id(), metadata.assetId(), new CaptureProvider.ByteRange(4, 12)).readAllBytes();
        assertArrayEquals(java.util.Arrays.copyOfRange(full, 4, 16), range);

        CaptureProvider.StepResult disturbed = provider.triggerStep(
                session.id(), "disturbed", trigger, "trigger:disturbed");
        assertNotEquals(reference.asset().sha256(), disturbed.asset().sha256());
        assertEquals(CaptureProvider.SessionState.COMPLETED,
                provider.getSession(session.id()).state());
        assertEquals(2, provider.getSession(session.id()).completedSteps());
        assertTrue(provider.getSession(session.id()).state().isTerminal());

        List<CaptureProvider.Event> events = provider.events(session.id(), 0).events();
        assertEquals(5, events.size());
        assertEquals("MOCK_SESSION_READY", events.getFirst().messageCode());
        assertEquals("MOCK_SESSION_COMPLETED", events.getLast().messageCode());
        assertEquals(disturbed.asset(), events.getLast().asset());
        assertFalse(provider.events(session.id(), events.getLast().eventId()).hasMore());
        assertTrue(provider.events(session.id(), events.getLast().eventId()).events().isEmpty());
    }

    @Test
    void triggerRejectsWrongOrderPatternAndIdempotencyMutation() {
        CaptureProvider.SessionRef bos = provider.createSession(bosPlan(), "create:bos");
        CaptureProvider.StepTrigger physical =
                new CaptureProvider.StepTrigger(null, null, NOW);

        CaptureProviderException order = assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(
                        bos.id(), "disturbed", physical, "trigger:wrong-order"));
        assertEquals("CAPTURE_STEP_OUT_OF_ORDER", order.messageCode());

        provider.triggerStep(bos.id(), "reference", physical, "trigger:one");
        CaptureProviderException keyReuse = assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(
                        bos.id(), "disturbed", physical, "trigger:one"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", keyReuse.messageCode());

        CaptureProvider.SessionRef stereo = provider.createSession(
                photometricPlan(), "create:stereo");
        CaptureProvider.StepTrigger wrongPattern = new CaptureProvider.StepTrigger(
                "pattern:wrong", PATTERN_HASH, NOW);
        CaptureProviderException mismatch = assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(
                        stereo.id(), "dark", wrongPattern, "trigger:dark"));
        assertEquals(CaptureProvider.FailureCode.INVALID_PLAN, mismatch.code());
        assertEquals("DISPLAYED_PATTERN_MISMATCH", mismatch.messageCode());
    }

    @Test
    void cancelIsIdempotentAndDoesNotRewriteTerminalSessions() {
        CaptureProvider.SessionRef session = provider.createSession(bosPlan(), "create:1");
        provider.cancel(session.id(), "cancel:1");
        provider.cancel(session.id(), "cancel:1");
        assertEquals(CaptureProvider.SessionState.CANCELLED,
                provider.getSession(session.id()).state());
        assertEquals("MOCK_SESSION_CANCELLED",
                provider.events(session.id(), 0).events().getLast().messageCode());

        CaptureProviderException terminal = assertThrows(CaptureProviderException.class,
                () -> provider.triggerStep(
                        session.id(), "reference",
                        new CaptureProvider.StepTrigger(null, null, NOW),
                        "trigger:after-cancel"));
        assertEquals("SESSION_TERMINAL", terminal.messageCode());
    }

    @Test
    void validationAndLookupFailuresAreTyped() throws IOException {
        CapturePlan wrongDevice = new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                "other-camera",
                new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.PNG),
                bosPlan().steps());
        assertFailure(CaptureProvider.FailureCode.DEVICE_UNAVAILABLE,
                () -> provider.createSession(wrongDevice, "create:wrong-device"));

        CapturePlan wrongFormat = new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                MockCaptureProvider.DEVICE_ID,
                new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.JPEG),
                bosPlan().steps());
        assertFailure(CaptureProvider.FailureCode.INVALID_PLAN,
                () -> provider.createSession(wrongFormat, "create:wrong-format"));

        assertFailure(CaptureProvider.FailureCode.SESSION_NOT_FOUND,
                () -> provider.getSession("missing-session"));

        CaptureProvider.SessionRef session = provider.createSession(bosPlan(), "create:ok");
        assertFailure(CaptureProvider.FailureCode.ASSET_NOT_FOUND,
                () -> provider.getAssetMetadata(session.id(), "missing-asset"));
        assertFailure(CaptureProvider.FailureCode.ASSET_NOT_FOUND,
                () -> provider.openAsset(
                        session.id(), "missing-asset", CaptureProvider.FullAsset.INSTANCE));

        CaptureProvider.StepResult result = provider.triggerStep(
                session.id(), "reference",
                new CaptureProvider.StepTrigger(null, null, NOW),
                "trigger:ok");
        long size = result.asset().byteLength();
        assertFailure(CaptureProvider.FailureCode.INVALID_PLAN,
                () -> provider.openAsset(
                        session.id(), result.asset().assetId(),
                        new CaptureProvider.ByteRange(size, 1)));

        assertThrows(IllegalArgumentException.class,
                () -> provider.createSession(bosPlan(), "bad key"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.events(session.id(), -1));
    }

    @Test
    void photometricPatternHashCanBeTriggeredExactly() {
        CapturePlan plan = photometricPlan();
        CaptureProvider.SessionRef session = provider.createSession(plan, "create:stereo");
        CapturePlan.Step first = plan.steps().getFirst();
        CaptureProvider.StepTrigger trigger = new CaptureProvider.StepTrigger(
                first.externalPatternId(), first.externalPatternSha256(), NOW);

        CaptureProvider.StepResult result = provider.triggerStep(
                session.id(), first.id(), trigger, "trigger:dark");
        assertEquals(first.id(), result.stepId());
        assertEquals(first.role(), result.asset().role());
    }

    private static void assertFailure(
            CaptureProvider.FailureCode expected,
            ThrowingRunnable operation) {
        CaptureProviderException exception = assertThrows(
                CaptureProviderException.class, operation::run);
        assertEquals(expected, exception.code());
    }

    private static CapturePlan bosPlan() {
        return new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                MockCaptureProvider.DEVICE_ID,
                new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.PNG),
                List.of(
                        step("reference", 0, CapturePlan.Role.REFERENCE, null, null),
                        step("disturbed", 1, CapturePlan.Role.DISTURBED, null, null)));
    }

    private static CapturePlan photometricPlan() {
        return new CapturePlan(
                CapturePlan.WorkflowType.DISPLAY_PHOTOMETRIC_STEREO,
                MockCaptureProvider.DEVICE_ID,
                new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.PNG),
                List.of(
                        step("dark", 0, CapturePlan.Role.DARK, "pattern:dark", PATTERN_HASH),
                        step("light-1", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, "pattern:1", PATTERN_HASH),
                        step("light-2", 2, CapturePlan.Role.DIRECTIONAL_LIGHT, "pattern:2", PATTERN_HASH),
                        step("light-3", 3, CapturePlan.Role.DIRECTIONAL_LIGHT, "pattern:3", PATTERN_HASH)));
    }

    private static CapturePlan.Step step(
            String id,
            int index,
            CapturePlan.Role role,
            String patternId,
            String patternHash) {
        return new CapturePlan.Step(
                id,
                index,
                role,
                CapturePlan.TriggerMode.EXTERNAL,
                Duration.ZERO,
                patternId,
                patternHash);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

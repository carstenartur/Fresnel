package org.fresnel.measurement;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider-neutral, declarative capture plan for one optical measurement.
 *
 * <p>The plan deliberately contains no commands, callback URLs, file-system
 * paths or provider-specific payloads. Construction validates all structural
 * and workflow invariants before a capture provider may contact a camera.</p>
 */
public record CapturePlan(
        WorkflowType workflowType,
        String deviceId,
        Requirements requirements,
        List<Step> steps) {

    public static final int MAX_STEPS = 256;
    public static final Duration MAX_SETTLE_DELAY = Duration.ofSeconds(30);
    public static final Duration MAX_DECLARED_SETTLE_TIME = Duration.ofMinutes(30);

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CapturePlan {
        Objects.requireNonNull(workflowType, "workflowType");
        deviceId = requireIdentifier(deviceId, "deviceId", MAX_IDENTIFIER_LENGTH);
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("capture plan must contain at least one step");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("capture plan exceeds " + MAX_STEPS + " steps");
        }

        Set<String> ids = new HashSet<>();
        Duration declaredSettleTime = Duration.ZERO;
        for (int index = 0; index < steps.size(); index++) {
            Step step = Objects.requireNonNull(steps.get(index), "steps[" + index + "]");
            if (step.sequenceIndex() != index) {
                throw new IllegalArgumentException(
                        "step sequenceIndex must match its ordered position: expected "
                                + index + " but was " + step.sequenceIndex());
            }
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("duplicate capture step id: " + step.id());
            }
            declaredSettleTime = declaredSettleTime.plus(step.settleDelay());
        }
        if (declaredSettleTime.compareTo(MAX_DECLARED_SETTLE_TIME) > 0) {
            throw new IllegalArgumentException(
                    "capture plan settle time exceeds " + MAX_DECLARED_SETTLE_TIME);
        }

        validateWorkflowSteps(workflowType, steps);
    }

    /** Returns the total declared delay before captures, excluding provider work. */
    public Duration declaredSettleTime() {
        return steps.stream()
                .map(Step::settleDelay)
                .reduce(Duration.ZERO, Duration::plus);
    }

    private static void validateWorkflowSteps(WorkflowType workflowType, List<Step> steps) {
        switch (workflowType) {
            case BACKGROUND_ORIENTED_SCHLIEREN -> validateBosSteps(steps);
            case DISPLAY_PHOTOMETRIC_STEREO -> validatePhotometricStereoSteps(steps);
        }
    }

    private static void validateBosSteps(List<Step> steps) {
        long references = steps.stream().filter(step -> step.role() == Role.REFERENCE).count();
        long disturbed = steps.stream().filter(step -> step.role() == Role.DISTURBED).count();
        boolean unsupportedRole = steps.stream().anyMatch(step ->
                step.role() != Role.REFERENCE && step.role() != Role.DISTURBED);
        if (references != 1) {
            throw new IllegalArgumentException("BOS capture plans require exactly one reference step");
        }
        if (disturbed < 1) {
            throw new IllegalArgumentException("BOS capture plans require at least one disturbed step");
        }
        if (unsupportedRole) {
            throw new IllegalArgumentException("BOS capture plans may contain only reference/disturbed steps");
        }
    }

    private static void validatePhotometricStereoSteps(List<Step> steps) {
        long directionalLights = steps.stream()
                .filter(step -> step.role() == Role.DIRECTIONAL_LIGHT)
                .count();
        boolean unsupportedRole = steps.stream().anyMatch(step ->
                step.role() != Role.DARK
                        && step.role() != Role.FLAT
                        && step.role() != Role.DIRECTIONAL_LIGHT);
        if (directionalLights < 3) {
            throw new IllegalArgumentException(
                    "photometric-stereo capture plans require at least three directional lights");
        }
        if (unsupportedRole) {
            throw new IllegalArgumentException(
                    "photometric-stereo plans may contain only dark, flat and directional-light steps");
        }
        for (Step step : steps) {
            if (step.externalPatternId() == null || step.externalPatternSha256() == null) {
                throw new IllegalArgumentException(
                        "photometric-stereo step requires an exact pattern id and SHA-256: "
                                + step.id());
            }
        }
    }

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

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    public enum WorkflowType {
        BACKGROUND_ORIENTED_SCHLIEREN,
        DISPLAY_PHOTOMETRIC_STEREO
    }

    public enum ImageFormat {
        JPEG,
        PNG,
        RAW_WITH_PREVIEW
    }

    public enum TriggerMode {
        AUTOMATIC,
        EXTERNAL
    }

    public enum Role {
        REFERENCE,
        DISTURBED,
        DARK,
        FLAT,
        DIRECTIONAL_LIGHT
    }

    /** Capture preferences that a provider must resolve explicitly. */
    public record Requirements(
            boolean lockExposure,
            boolean lockFocus,
            ImageFormat preferredImageFormat) {

        public Requirements {
            Objects.requireNonNull(preferredImageFormat, "preferredImageFormat");
        }
    }

    /** One immutable, ordered capture step. */
    public record Step(
            String id,
            int sequenceIndex,
            Role role,
            TriggerMode triggerMode,
            Duration settleDelay,
            String externalPatternId,
            String externalPatternSha256) {

        public Step {
            id = requireIdentifier(id, "step id", 64);
            if (sequenceIndex < 0 || sequenceIndex >= MAX_STEPS) {
                throw new IllegalArgumentException(
                        "sequenceIndex must be between 0 and " + (MAX_STEPS - 1));
            }
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(triggerMode, "triggerMode");
            Objects.requireNonNull(settleDelay, "settleDelay");
            if (settleDelay.isNegative() || settleDelay.compareTo(MAX_SETTLE_DELAY) > 0) {
                throw new IllegalArgumentException(
                        "settleDelay must be between zero and " + MAX_SETTLE_DELAY);
            }
            if ((externalPatternId == null) != (externalPatternSha256 == null)) {
                throw new IllegalArgumentException(
                        "external pattern id and SHA-256 must either both be present or both be absent");
            }
            if (externalPatternId != null) {
                externalPatternId = requireIdentifier(
                        externalPatternId, "externalPatternId", MAX_IDENTIFIER_LENGTH);
                externalPatternSha256 = requireSha256(
                        externalPatternSha256, "externalPatternSha256");
            }
        }
    }
}

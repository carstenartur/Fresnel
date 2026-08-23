package org.fresnel.measurement;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapturePlanTest {

    private static final CapturePlan.Requirements REQUIREMENTS =
            new CapturePlan.Requirements(true, true, CapturePlan.ImageFormat.RAW_WITH_PREVIEW);

    @Test
    void validBosPlanIsNormalizedImmutableAndReportsSettleTime() {
        List<CapturePlan.Step> mutable = new ArrayList<>(List.of(
                step(" reference ", 0, CapturePlan.Role.REFERENCE, Duration.ofMillis(100), null),
                step("disturbed-1", 1, CapturePlan.Role.DISTURBED, Duration.ofMillis(250), null)));

        CapturePlan plan = new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                " camera:one ", REQUIREMENTS, mutable);
        mutable.clear();

        assertEquals("camera:one", plan.deviceId());
        assertEquals("reference", plan.steps().getFirst().id());
        assertEquals(2, plan.steps().size());
        assertEquals(Duration.ofMillis(350), plan.declaredSettleTime());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
    }

    @Test
    void bosPlanRequiresExactlyOneReferenceAndOneDisturbedFrame() {
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("d", 0, CapturePlan.Role.DISTURBED, Duration.ZERO, null))));
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("r1", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null),
                step("r2", 1, CapturePlan.Role.REFERENCE, Duration.ZERO, null),
                step("d", 2, CapturePlan.Role.DISTURBED, Duration.ZERO, null))));
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("r", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null))));
    }

    @Test
    void bosPlanRejectsPhotometricRoles() {
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("r", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null),
                step("light", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "pattern"))));
    }

    @Test
    void validPhotometricStereoPlanRequiresMappedLightPatterns() {
        CapturePlan plan = photometricPlan(List.of(
                step("dark", 0, CapturePlan.Role.DARK, Duration.ZERO, "pattern-dark"),
                step("light-1", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ofMillis(20), "p1"),
                step("light-2", 2, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ofMillis(20), "p2"),
                step("light-3", 3, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ofMillis(20), "p3")));

        assertEquals(4, plan.steps().size());
        assertEquals("pattern-dark", plan.steps().getFirst().externalPatternId());
    }

    @Test
    void photometricStereoRejectsTooFewLightsMissingPatternAndBosRole() {
        assertThrows(IllegalArgumentException.class, () -> photometricPlan(List.of(
                step("l1", 0, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p1"),
                step("l2", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p2"))));
        assertThrows(IllegalArgumentException.class, () -> photometricPlan(List.of(
                step("l1", 0, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p1"),
                step("l2", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p2"),
                step("l3", 2, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, null))));
        assertThrows(IllegalArgumentException.class, () -> photometricPlan(List.of(
                step("r", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, "r"),
                step("l1", 1, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p1"),
                step("l2", 2, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p2"),
                step("l3", 3, CapturePlan.Role.DIRECTIONAL_LIGHT, Duration.ZERO, "p3"))));
    }

    @Test
    void planRejectsEmptyExcessiveMisorderedDuplicateAndNullSteps() {
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of()));

        List<CapturePlan.Step> tooMany = IntStream.range(0, CapturePlan.MAX_STEPS + 1)
                .mapToObj(index -> step("s" + index, index,
                        index == 0 ? CapturePlan.Role.REFERENCE : CapturePlan.Role.DISTURBED,
                        Duration.ZERO, null))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> bosPlan(tooMany));

        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("r", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null),
                step("d", 2, CapturePlan.Role.DISTURBED, Duration.ZERO, null))));
        assertThrows(IllegalArgumentException.class, () -> bosPlan(List.of(
                step("same", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null),
                step("same", 1, CapturePlan.Role.DISTURBED, Duration.ZERO, null))));

        List<CapturePlan.Step> withNull = new ArrayList<>();
        withNull.add(step("r", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> bosPlan(withNull));
    }

    @Test
    void planRejectsExcessiveAggregateSettleTime() {
        List<CapturePlan.Step> steps = IntStream.range(0, 61)
                .mapToObj(index -> step(
                        "s" + index,
                        index,
                        index == 0 ? CapturePlan.Role.REFERENCE : CapturePlan.Role.DISTURBED,
                        Duration.ofSeconds(30),
                        null))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> bosPlan(steps));
    }

    @Test
    void stepRejectsBadIdentifiersIndexesAndDelays() {
        assertThrows(IllegalArgumentException.class, () ->
                step("bad id", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () ->
                step("x", -1, CapturePlan.Role.REFERENCE, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () ->
                step("x", CapturePlan.MAX_STEPS, CapturePlan.Role.REFERENCE, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () ->
                step("x", 0, CapturePlan.Role.REFERENCE, Duration.ofMillis(-1), null));
        assertThrows(IllegalArgumentException.class, () ->
                step("x", 0, CapturePlan.Role.REFERENCE, Duration.ofSeconds(31), null));
        assertThrows(IllegalArgumentException.class, () ->
                step("x", 0, CapturePlan.Role.REFERENCE, Duration.ZERO, "bad pattern"));
    }

    @Test
    void planAndRequirementsRejectMissingRequiredValues() {
        assertThrows(NullPointerException.class, () ->
                new CapturePlan.Requirements(true, true, null));
        assertThrows(NullPointerException.class, () ->
                new CapturePlan(null, "camera", REQUIREMENTS, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new CapturePlan(CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                        " ", REQUIREMENTS, List.of()));
        assertThrows(NullPointerException.class, () ->
                new CapturePlan(CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                        "camera", null, List.of()));
    }

    private static CapturePlan bosPlan(List<CapturePlan.Step> steps) {
        return new CapturePlan(
                CapturePlan.WorkflowType.BACKGROUND_ORIENTED_SCHLIEREN,
                "camera", REQUIREMENTS, steps);
    }

    private static CapturePlan photometricPlan(List<CapturePlan.Step> steps) {
        return new CapturePlan(
                CapturePlan.WorkflowType.DISPLAY_PHOTOMETRIC_STEREO,
                "camera", REQUIREMENTS, steps);
    }

    private static CapturePlan.Step step(
            String id,
            int index,
            CapturePlan.Role role,
            Duration delay,
            String patternId) {
        return new CapturePlan.Step(
                id, index, role, CapturePlan.TriggerMode.EXTERNAL, delay, patternId);
    }
}

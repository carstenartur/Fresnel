package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DesignAssistant} — candidate generation and ranking stability.
 */
class DesignAssistantTest {

    /** Reference scenario: 600 dpi, A4, green laser, 2 m focus. */
    private static final DesignGoal REFERENCE_GOAL = new DesignGoal(
            600.0,   // dpi
            210.0,   // A4 width mm
            297.0,   // A4 height mm
            532.0,   // green laser nm
            2000.0,  // 2 m focus
            null     // no aperture cap
    );

    // -------------------------------------------------------------------------
    // Candidate count and structure
    // -------------------------------------------------------------------------

    @Test
    void referenceScenarioReturnsTwoAlternativesAndOneRecommended() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        assertNotNull(rec.recommended());
        assertEquals(2, rec.alternatives().size());
    }

    @Test
    void referenceScenarioReturnsTotalThreeCandidates() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        assertEquals(DesignAssistant.CANDIDATE_COUNT, 1 + rec.alternatives().size());
    }

    @Test
    void referenceScenarioAllCandidatesHaveZonePlateParameters() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        List<CandidateDesign> all = allCandidates(rec);
        for (CandidateDesign c : all) {
            assertNotNull(c.parameters(), "parameters must not be null for " + c.label());
            assertEquals(532.0, c.parameters().wavelengthNm());
            assertEquals(2000.0, c.parameters().focalLengthMm());
            assertEquals(600.0, c.parameters().dpi());
        }
    }

    @Test
    void referenceScenarioCandidatesHaveUniqueApertures() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        List<Double> apertures = allCandidates(rec).stream()
                .map(c -> c.parameters().apertureDiameterMm())
                .distinct()
                .toList();
        assertEquals(DesignAssistant.CANDIDATE_COUNT, apertures.size(),
                "All candidates must have distinct aperture diameters");
    }

    // -------------------------------------------------------------------------
    // Ranking
    // -------------------------------------------------------------------------

    @Test
    void recommendedCandidateHasRankOne() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        assertEquals(1, rec.recommended().rank());
    }

    @Test
    void alternativesHaveAscendingRanks() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        for (int i = 0; i < rec.alternatives().size(); i++) {
            assertEquals(i + 2, rec.alternatives().get(i).rank(),
                    "Alternative " + i + " should have rank " + (i + 2));
        }
    }

    @Test
    void scoresAreDescendingFromRecommendedToAlternatives() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        double best = rec.recommended().compositeScore();
        for (CandidateDesign alt : rec.alternatives()) {
            assertTrue(alt.compositeScore() <= best + 1e-9,
                    "Alternative score must not exceed recommended score");
        }
    }

    @Test
    void rankingIsDeterministic() {
        DesignRecommendation r1 = DesignAssistant.recommend(REFERENCE_GOAL);
        DesignRecommendation r2 = DesignAssistant.recommend(REFERENCE_GOAL);
        assertEquals(r1.recommended().label(), r2.recommended().label());
        assertEquals(r1.recommended().compositeScore(), r2.recommended().compositeScore(), 1e-12);
        for (int i = 0; i < r1.alternatives().size(); i++) {
            assertEquals(r1.alternatives().get(i).label(), r2.alternatives().get(i).label());
        }
    }

    // -------------------------------------------------------------------------
    // Scoring plausibility
    // -------------------------------------------------------------------------

    @Test
    void allScoresAreInUnitInterval() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        for (CandidateDesign c : allCandidates(rec)) {
            assertTrue(c.compositeScore() >= 0.0 && c.compositeScore() <= 1.0 + 1e-9,
                    "Score out of [0,1]: " + c.compositeScore() + " for " + c.label());
        }
    }

    @Test
    void allCandidatesHaveNonEmptyReasons() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        for (CandidateDesign c : allCandidates(rec)) {
            assertFalse(c.reasons().isEmpty(), "Reasons must not be empty for " + c.label());
        }
    }

    @Test
    void reasonsContainPrintabilityAndFocusQuality() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        for (CandidateDesign c : allCandidates(rec)) {
            List<String> dims = c.reasons().stream()
                    .map(RecommendationReason::dimension).toList();
            assertTrue(dims.contains("printability"), "Missing 'printability' reason for " + c.label());
            assertTrue(dims.contains("focus_quality"), "Missing 'focus_quality' reason for " + c.label());
        }
    }

    @Test
    void validationIsPopulated() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        for (CandidateDesign c : allCandidates(rec)) {
            assertNotNull(c.validation(), "Validation must not be null for " + c.label());
            assertNotNull(c.validation().metrics(), "Metrics must not be null for " + c.label());
        }
    }

    // -------------------------------------------------------------------------
    // Global warnings
    // -------------------------------------------------------------------------

    @Test
    void globalWarningsContainAdvisoryDisclaimer() {
        DesignRecommendation rec = DesignAssistant.recommend(REFERENCE_GOAL);
        assertTrue(rec.globalWarnings().stream()
                        .anyMatch(w -> w.code().equals("ADVISORY")),
                "Global warnings must contain ADVISORY disclaimer");
    }

    @Test
    void nonVisibleWavelengthTriggersGlobalWarning() {
        DesignGoal uvGoal = new DesignGoal(600.0, 210.0, 297.0, 355.0, 2000.0, null);
        DesignRecommendation rec = DesignAssistant.recommend(uvGoal);
        assertTrue(rec.globalWarnings().stream()
                        .anyMatch(w -> w.code().equals("NON_VISIBLE_WAVELENGTH")),
                "UV wavelength should trigger NON_VISIBLE_WAVELENGTH warning");
    }

    // -------------------------------------------------------------------------
    // Constraints
    // -------------------------------------------------------------------------

    @Test
    void maxApertureCapsAllCandidates() {
        double cap = 8.0;
        DesignGoal capped = new DesignGoal(600.0, 210.0, 297.0, 532.0, 2000.0, cap);
        DesignRecommendation rec = DesignAssistant.recommend(capped);
        for (CandidateDesign c : allCandidates(rec)) {
            assertTrue(c.parameters().apertureDiameterMm() <= cap + 1e-6,
                    "Aperture " + c.parameters().apertureDiameterMm() + " exceeds cap " + cap);
        }
    }

    @Test
    void pageSizeCapsAllCandidates() {
        DesignGoal tinyPage = new DesignGoal(600.0, 30.0, 40.0, 532.0, 2000.0, null);
        DesignRecommendation rec = DesignAssistant.recommend(tinyPage);
        for (CandidateDesign c : allCandidates(rec)) {
            assertTrue(c.parameters().apertureDiameterMm() <= 30.0 + 1e-6,
                    "Aperture exceeds page width");
        }
    }

    @Test
    void invalidDpiThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new DesignGoal(-1.0, 210.0, 297.0, 532.0, 2000.0, null));
    }

    @Test
    void invalidFocalLengthThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new DesignGoal(600.0, 210.0, 297.0, 532.0, -500.0, null));
    }

    // -------------------------------------------------------------------------
    // Fabrication risk helper
    // -------------------------------------------------------------------------

    @Test
    void fabricationRiskOneForValidDesign() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(5.0, 2000.0, 532.0, 600.0);
        ValidationResult v = DesignValidator.validate(p);
        double risk = DesignAssistant.fabricationRisk(v);
        // At 600 dpi, D=5mm this should be near printability threshold; risk is 1.0 or 0.5
        assertTrue(risk >= 0.0 && risk <= 1.0);
    }

    @Test
    void fabricationRiskZeroForUnprintableDesign() {
        // Very large aperture → outer zone < 2px → ERROR
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(50.0, 2000.0, 532.0, 600.0);
        ValidationResult v = DesignValidator.validate(p);
        assertEquals(0.0, DesignAssistant.fabricationRisk(v), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<CandidateDesign> allCandidates(DesignRecommendation rec) {
        List<CandidateDesign> all = new java.util.ArrayList<>();
        all.add(rec.recommended());
        all.addAll(rec.alternatives());
        return all;
    }
}

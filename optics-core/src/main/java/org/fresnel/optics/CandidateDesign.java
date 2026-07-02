package org.fresnel.optics;

import java.util.List;

/**
 * A generated and evaluated design candidate produced by the Optical Design Assistant.
 *
 * @param label          short human-readable label (e.g. "Compact Zone Plate")
 * @param parameters     zone plate parameters for this candidate
 * @param rank           1-based rank among all candidates (1 = best)
 * @param compositeScore normalized composite score in [0, 1]; higher is better
 * @param reasons        per-dimension scoring notes explaining the rank
 * @param warnings       design-specific assistant warnings (e.g. near printability limit)
 * @param validation     full validation result including metrics and optical quality
 */
public record CandidateDesign(
        String label,
        SingleZonePlateParameters parameters,
        int rank,
        double compositeScore,
        List<RecommendationReason> reasons,
        List<AssistantWarning> warnings,
        ValidationResult validation
) {}

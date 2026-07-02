package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.optics.AssistantWarning;
import org.fresnel.optics.RecommendationReason;
import org.fresnel.optics.SingleZonePlateParameters;

import java.util.List;

/**
 * REST response for a single design candidate returned by the Optical Design Assistant.
 *
 * @param label          short human-readable label
 * @param parameters     zone plate parameters for this candidate
 * @param rank           1-based rank among all candidates (1 = best)
 * @param compositeScore normalized composite score in [0, 1]; higher is better
 * @param reasons        per-dimension scoring notes
 * @param warnings       design-specific assistant warnings
 * @param validation     validation result including metrics and optical quality
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CandidateDesignDto(
        String label,
        SingleZonePlateParameters parameters,
        int rank,
        double compositeScore,
        List<RecommendationReason> reasons,
        List<AssistantWarning> warnings,
        ValidationResponse validation
) {}

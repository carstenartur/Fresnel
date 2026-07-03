package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.fresnel.optics.AssistantWarning;

import java.util.List;

/**
 * REST response for {@code POST /api/assistant/recommend}.
 *
 * @param recommended    the highest-ranked candidate design
 * @param alternatives   remaining candidates in descending rank order
 * @param globalWarnings advisory warnings that apply to the whole recommendation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantRecommendationResponse(
        CandidateDesignDto recommended,
        List<CandidateDesignDto> alternatives,
        List<AssistantWarning> globalWarnings
) {}

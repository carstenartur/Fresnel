package org.fresnel.backend.api;

import jakarta.validation.Valid;
import org.fresnel.optics.CandidateDesign;
import org.fresnel.optics.DesignAssistant;
import org.fresnel.optics.DesignRecommendation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the goal-driven Optical Design Assistant.
 *
 * <p>{@code POST /api/assistant/recommend} accepts a {@link DesignGoalRequest} describing the
 * user's printer, page size, light source, and target focal distance.  It returns a
 * {@link AssistantRecommendationResponse} containing:
 * <ul>
 *   <li>the recommended Zone Plate design with a human-readable explanation</li>
 *   <li>alternative candidates ranked by a composite score</li>
 *   <li>global advisory warnings</li>
 * </ul>
 *
 * <p><strong>This endpoint is advisory.</strong>  All results are based on the paraxial
 * thin-lens approximation and stated design assumptions.  Designs should be validated
 * experimentally before use.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    @PostMapping(value = "/recommend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AssistantRecommendationResponse recommend(@Valid @RequestBody DesignGoalRequest req) {
        DesignRecommendation rec = DesignAssistant.recommend(req.toGoal());
        return toResponse(rec);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private AssistantRecommendationResponse toResponse(DesignRecommendation rec) {
        CandidateDesignDto recommended = toDto(rec.recommended());
        java.util.List<CandidateDesignDto> alternatives = rec.alternatives().stream()
                .map(this::toDto)
                .toList();
        return new AssistantRecommendationResponse(recommended, alternatives, rec.globalWarnings());
    }

    private CandidateDesignDto toDto(CandidateDesign c) {
        return new CandidateDesignDto(
                c.label(),
                c.parameters(),
                c.rank(),
                c.compositeScore(),
                c.reasons(),
                c.warnings(),
                ValidationResponse.from(c.validation()));
    }
}

package org.fresnel.optics;

import java.util.List;

/**
 * Complete recommendation returned by the Optical Design Assistant.
 *
 * @param recommended    the highest-ranked candidate design
 * @param alternatives   remaining candidates in descending rank order (rank 2, 3, …)
 * @param globalWarnings advisory warnings that apply to the whole recommendation
 *                       (e.g. the advisory disclaimer)
 */
public record DesignRecommendation(
        CandidateDesign recommended,
        List<CandidateDesign> alternatives,
        List<AssistantWarning> globalWarnings
) {}

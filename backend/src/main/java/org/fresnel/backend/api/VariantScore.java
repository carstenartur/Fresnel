package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ranking result for one design variant.
 *
 * <p>A higher {@code score} is better. Scores are normalized to the range
 * [0, 1] across all variants in the comparison, so the best variant in
 * each run has score 1.0. The ranking is deterministic: ties are broken
 * by the order in which the variants were submitted.
 *
 * @param rank        1-based rank among all variants (1 = best)
 * @param score       normalized composite score in [0, 1]
 * @param explanation human-readable breakdown of the score components
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantScore(
        int    rank,
        double score,
        String explanation
) {}

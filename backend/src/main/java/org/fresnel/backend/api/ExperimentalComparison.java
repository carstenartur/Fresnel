package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Derived theory-vs-experiment comparison metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentalComparison(
        Double targetFocalLengthMm,
        Double measuredFocalLengthMm,
        Double focalLengthErrorMm,
        Double focalLengthErrorPercent,
        Double measuredSpotSizeMicrons,
        String focusRating,
        String summary
) {}

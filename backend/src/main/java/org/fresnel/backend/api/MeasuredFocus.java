package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One observed focus measurement from an experiment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeasuredFocus(
        String label,
        Double measuredFocalLengthMm,
        Double measuredSpotSizeMicrons,
        String focusRating,
        String notes
) {}

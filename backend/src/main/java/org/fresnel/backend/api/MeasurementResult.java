package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Measured results captured for a design under a specific setup.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeasurementResult(
        Double targetFocalLengthMm,
        List<MeasuredFocus> measuredFoci
) {
    public MeasurementResult {
        measuredFoci = measuredFoci == null ? List.of() : List.copyOf(measuredFoci);
    }
}

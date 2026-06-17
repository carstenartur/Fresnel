package org.fresnel.backend.api;

import org.fresnel.optics.DesignValidator;
import org.fresnel.optics.OpticalQualityReport;
import org.fresnel.optics.DesignMetrics;
import org.fresnel.optics.ValidationResult;

import java.util.List;

/** REST response for a validation request. */
public record ValidationResponse(
        boolean valid,
        List<WarningDto> warnings,
        DesignMetrics metrics,
        OpticalQualityReport qualityReport
) {

    public record WarningDto(String code, String message, String severity) {}

    public static ValidationResponse from(ValidationResult v) {
        List<WarningDto> warnings = v.warnings().stream()
                .map(w -> new WarningDto(w.code(), w.message(), w.severity().name()))
                .toList();
        OpticalQualityReport report = v.parameters() != null
                ? DesignValidator.computeOpticalQualityReport(v.parameters())
                : null;
        return new ValidationResponse(v.valid(), warnings, v.metrics(), report);
    }
}

package org.fresnel.backend.api;

import org.fresnel.optics.DesignMetrics;
import org.fresnel.optics.ValidationResult;

import java.util.List;

/** REST response for a validation request. */
public record ValidationResponse(
        boolean valid,
        List<WarningDto> warnings,
        DesignMetrics metrics
) {

    public record WarningDto(String code, String message, String severity) {}

    public static ValidationResponse from(ValidationResult v) {
        List<WarningDto> warnings = v.warnings().stream()
                .map(w -> new WarningDto(w.code(), w.message(), w.severity().name()))
                .toList();
        return new ValidationResponse(v.valid(), warnings, v.metrics());
    }
}

package org.fresnel.optics;

import java.util.List;

/**
 * Result of validating an optical design: whether it is printable, the
 * computed metrics and any warnings.
 */
public record ValidationResult(
        boolean valid,
        List<Warning> warnings,
        DesignMetrics metrics
) {

    public ValidationResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** A printability or quality warning. */
    public record Warning(String code, String message, Severity severity) {
        public enum Severity { INFO, WARNING, ERROR }
    }
}

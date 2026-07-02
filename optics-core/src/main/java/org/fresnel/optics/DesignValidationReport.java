package org.fresnel.optics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin-independent deterministic validation report.
 */
public record DesignValidationReport(
        String pluginId,
        String parameterHash,
        Map<String, String> parameterSnapshot,
        Double wavelengthMinNm,
        Double wavelengthMaxNm,
        Double apertureDiameterMm,
        List<Double> targetFocalDistancesMm,
        Double pixelSizeMicrons,
        List<ValidationAssumption> assumptions,
        List<ValidationMetric> metrics,
        List<ValidationFinding> findings
) {
    public DesignValidationReport {
        parameterSnapshot = java.util.Collections.unmodifiableMap(
                parameterSnapshot == null ? Map.of() : new LinkedHashMap<>(parameterSnapshot));
        targetFocalDistancesMm = targetFocalDistancesMm == null ? List.of() : List.copyOf(targetFocalDistancesMm);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** Returns {@code true} if no ERROR finding exists. */
    public boolean valid() {
        return findings.stream().noneMatch(f -> f.severity() == ValidationSeverity.ERROR);
    }
}

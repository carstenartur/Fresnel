package org.fresnel.optics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin-independent deterministic validation report.
 *
 * <p>{@code valid} is part of the public data contract rather than a serializer-
 * specific derived getter. The canonical constructor always recomputes it from
 * the normalized findings, so callers cannot create a report whose flag
 * contradicts its ERROR findings.</p>
 *
 * <p>The component is boxed so older persisted experiment records that predate
 * this serialized field can still be read. A missing JSON property binds as
 * {@code null}; the constructor immediately replaces it with the canonical
 * findings-derived value.</p>
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
        List<ValidationFinding> findings,
        Boolean valid
) {
    public DesignValidationReport {
        parameterSnapshot = java.util.Collections.unmodifiableMap(
                parameterSnapshot == null ? Map.of() : new LinkedHashMap<>(parameterSnapshot));
        targetFocalDistancesMm = targetFocalDistancesMm == null
                ? List.of()
                : List.copyOf(targetFocalDistancesMm);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        findings = findings == null ? List.of() : List.copyOf(findings);
        valid = findings.stream().noneMatch(
                finding -> finding.severity() == ValidationSeverity.ERROR);
    }

    /**
     * Backward-compatible source constructor used by report factories. The
     * canonical constructor derives the final flag after defensive copies.
     */
    public DesignValidationReport(
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
        this(
                pluginId,
                parameterHash,
                parameterSnapshot,
                wavelengthMinNm,
                wavelengthMaxNm,
                apertureDiameterMm,
                targetFocalDistancesMm,
                pixelSizeMicrons,
                assumptions,
                metrics,
                findings,
                null);
    }
}

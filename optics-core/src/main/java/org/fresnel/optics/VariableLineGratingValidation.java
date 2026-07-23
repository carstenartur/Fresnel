package org.fresnel.optics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic validation report for the variable-line grating plugin. */
public final class VariableLineGratingValidation {

    private VariableLineGratingValidation() {}

    public static DesignValidationReport report(VariableLineGratingParameters p) {
        return report(p, null);
    }

    public static DesignValidationReport report(
            VariableLineGratingParameters p,
            PrinterRasterProfile profile) {
        VariableLineGratingAnalysis.Result analysis = VariableLineGratingAnalysis.analyze(p, profile);
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("widthMm", fmt(p.widthMm()));
        snapshot.put("heightMm", fmt(p.heightMm()));
        snapshot.put("lineOrientation", p.lineOrientation().name());
        snapshot.put("testedPageAxis", p.lineOrientation() == LineOrientation.VERTICAL ? "PAGE_X" : "PAGE_Y");
        snapshot.put("testedDeviceAxis", analysis.testedDeviceAxis().name());
        snapshot.put("selectedAxisDpi", fmt(analysis.selectedAxisDpi()));
        snapshot.put("startPitchUm", fmt(p.startPitchUm()));
        snapshot.put("endPitchUm", fmt(p.endPitchUm()));
        snapshot.put("progression", p.progression().name());
        snapshot.put("progressionDirection", p.progressionDirection().name());
        snapshot.put("dutyCycle", fmt(p.dutyCycle()));
        snapshot.put("phaseOffsetCycles", fmt(p.phaseOffsetCycles()));
        snapshot.put("polarity", p.polarity().name());
        snapshot.put("marginMm", fmt(p.marginMm()));
        snapshot.put("annotationSizeMm", fmt(p.annotationSizeMm()));
        snapshot.put("showAxis", Boolean.toString(p.showAxis()));
        snapshot.put("axisQuantity", p.axisQuantity().name());
        snapshot.put("tickCount", Integer.toString(p.tickCount()));
        snapshot.put("showReferenceBands", Boolean.toString(p.showReferenceBands()));
        snapshot.put("referenceBandSizeMm", fmt(p.referenceBandSizeMm()));
        snapshot.put("dpi", fmt(p.dpi()));
        if (profile != null) {
            snapshot.put("printerProfileId", profile.id());
            snapshot.put("printerProfileVersion", Integer.toString(profile.version()));
            snapshot.put("profileDpiX", Integer.toString(profile.dpiX()));
            snapshot.put("profileDpiY", Integer.toString(profile.dpiY()));
            snapshot.put("pageXAxisMapsTo", profile.pageXAxisMapsTo().name());
            snapshot.put("pageYAxisMapsTo", profile.pageYAxisMapsTo().name());
        }

        List<ValidationMetric> metrics = new ArrayList<>();
        metrics.add(metric("MIN_PITCH_UM", "Minimum pitch", analysis.minPitchUm(), "µm"));
        metrics.add(metric("MAX_PITCH_UM", "Maximum pitch", analysis.maxPitchUm(), "µm"));
        metrics.add(metric("MIN_OPAQUE_FEATURE_UM", "Minimum opaque feature",
                analysis.minimumOpaqueFeatureUm(), "µm"));
        metrics.add(metric("MIN_CLEAR_FEATURE_UM", "Minimum clear feature",
                analysis.minimumClearFeatureUm(), "µm"));
        metrics.add(metric("MIN_DOTS_PER_PERIOD", "Minimum selected-axis dots per period",
                analysis.minDotsPerPeriod(), "dots"));
        metrics.add(metric("MAX_DOTS_PER_PERIOD", "Maximum selected-axis dots per period",
                analysis.maxDotsPerPeriod(), "dots"));
        metrics.add(metric("MIN_DOTS_PER_OPAQUE_FEATURE", "Minimum dots per opaque feature",
                analysis.minDotsPerOpaqueFeature(), "dots"));
        metrics.add(metric("MIN_DOTS_PER_CLEAR_FEATURE", "Minimum dots per clear feature",
                analysis.minDotsPerClearFeature(), "dots"));
        metrics.add(metric("NOMINAL_CYCLE_COUNT", "Integrated nominal cycle count",
                analysis.nominalCycleCount(), "cycles"));

        List<ValidationFinding> findings = new ArrayList<>();
        double minimumFeatureDots = Math.min(
                analysis.minDotsPerOpaqueFeature(), analysis.minDotsPerClearFeature());
        if (minimumFeatureDots < 1.0 || analysis.minDotsPerPeriod() < 2.0) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    "SEVERE_UNDERSAMPLING",
                    "The finest region is below two selected-axis device dots per period or one dot per feature. It is intentionally allowed for calibration, but reproduction is not expected to be faithful.",
                    ValidationSeverity.WARNING));
        } else if (analysis.minDotsPerPeriod() < 4.0) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    "UNDERSAMPLED_REGION",
                    "Part of the progression is below four selected-axis device dots per period.",
                    ValidationSeverity.WARNING));
        }
        if (profile == null) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    "NOMINAL_AXIS_MAPPING",
                    "Validation uses the design DPI and nominal page-axis mapping. Select a printer profile for device-axis-specific PCL validation.",
                    ValidationSeverity.INFO));
        }
        if (p.showReferenceBands()) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    "REFERENCE_BANDS_ENABLED",
                    "Constant-pitch reference bands use the configured start and end pitches outside the variable central band.",
                    ValidationSeverity.INFO));
        }
        findings.add(new ValidationFinding(
                ValidationLayer.EXPERIMENTAL_HOOKS,
                "PRINTER_CALIBRATION_PENDING",
                "No measured printer calibration result is attached to this deterministic report.",
                ValidationSeverity.INFO));

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(
                        ValidationLayer.ANALYTICAL_OPTICS,
                        "Line placement is derived from the integral of local spatial frequency; no naive coordinate modulo is used.",
                        false),
                new ValidationAssumption(
                        ValidationLayer.NUMERICAL_PROPAGATION,
                        "This calibration target is geometric and does not model optical propagation.",
                        true),
                new ValidationAssumption(
                        ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Dots-per-period is evaluated only along the page axis perpendicular to the selected line orientation.",
                        false),
                new ValidationAssumption(
                        ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Driver resampling, toner spread, media transport and transparency dimensional change require physical measurement.",
                        true)
        );

        return new DesignValidationReport(
                "variable-line-grating",
                stableHash(snapshot),
                snapshot,
                null,
                null,
                Math.max(p.widthMm(), p.heightMm()),
                List.of(),
                Units.pixelSizeMicrons(p.dpi()),
                assumptions,
                metrics,
                findings);
    }

    private static ValidationMetric metric(String key, String label, double value, String unit) {
        return new ValidationMetric(
                ValidationLayer.MANUFACTURING_PRINTABILITY, key, label, value, unit);
    }

    private static String stableHash(Map<String, String> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            snapshot.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.12g", value);
    }
}

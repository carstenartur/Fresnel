package org.fresnel.optics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Factory methods for plugin-independent validation reports. */
public final class DesignValidationReports {

    private DesignValidationReports() {}

    public static DesignValidationReport forZonePlate(SingleZonePlateParameters p) {
        DesignMetrics m = DesignValidator.computeMetrics(p);
        OpticalQualityReport q = DesignValidator.computeOpticalQualityReport(p);

        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("apertureDiameterMm", fmt(p.apertureDiameterMm()));
        snapshot.put("focalLengthMm", fmt(p.focalLengthMm()));
        snapshot.put("wavelengthNm", fmt(p.wavelengthNm()));
        snapshot.put("dpi", fmt(p.dpi()));
        snapshot.put("targetOffsetXmm", fmt(p.targetOffsetXmm()));
        snapshot.put("targetOffsetYmm", fmt(p.targetOffsetYmm()));
        snapshot.put("maskType", p.maskType().name());
        snapshot.put("polarity", p.polarity().name());

        List<ValidationMetric> metrics = new ArrayList<>();
        metrics.add(new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "NA", "Numerical aperture", q.numericalAperture(), ""));
        metrics.add(new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "F_NUMBER", "f-number", q.fNumber(), ""));
        metrics.add(new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "AIRY_DISK_UM", "Airy disk diameter", q.airyDiskDiameterMicrons(), "µm"));
        metrics.add(new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "CHROMATIC_SHIFT_MM", "Chromatic focal shift", q.chromaticFocalShiftMm(), "mm"));
        metrics.add(new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "OUTER_ZONE_UM", "Outer zone width", m.outerZoneWidthMicrons(), "µm"));
        metrics.add(new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXELS_PER_OUTER_ZONE", "Pixels per outer zone", m.pixelsPerOuterZone(), "px"));
        metrics.add(new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PRINTER_PIXEL_UM", "Printer pixel size", m.printerPixelMicrons(), "µm"));

        List<ValidationFinding> findings = new ArrayList<>();
        ValidationResult base = DesignValidator.validate(p);
        for (ValidationResult.Warning w : base.warnings()) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    w.code(),
                    w.message(),
                    switch (w.severity()) {
                        case INFO -> ValidationSeverity.INFO;
                        case WARNING -> ValidationSeverity.WARNING;
                        case ERROR -> ValidationSeverity.ERROR;
                    }
            ));
        }
        findings.add(notApplicableExperimentalFinding());

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "Paraxial scalar diffraction formulas are used (NA assumed small).", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Chromatic behavior is approximated by f(λ) ∝ 1/λ over the configured range.", false),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Printability heuristic uses pixels per outermost zone and does not model printer MTF.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Laboratory measurements are not part of deterministic validation yet.", true)
        );

        return new DesignValidationReport(
                "zone-plate",
                stableHash(snapshot),
                snapshot,
                p.wavelengthNm(),
                p.wavelengthNm(),
                p.apertureDiameterMm(),
                List.of(p.focalLengthMm()),
                Units.pixelSizeMm(p.dpi()) * 1000.0,
                assumptions,
                metrics,
                findings
        );
    }

    public static DesignValidationReport forRgbZonePlate(SingleZonePlateParameters base,
                                                         double redNm, double greenNm, double blueNm) {
        DesignMetrics m = DesignValidator.computeMetrics(base);

        double minNm = Math.min(redNm, Math.min(greenNm, blueNm));
        double maxNm = Math.max(redNm, Math.max(greenNm, blueNm));
        double fMin = DesignValidator.focalLengthAtWavelength(base.focalLengthMm(), base.wavelengthNm(), minNm);
        double fMax = DesignValidator.focalLengthAtWavelength(base.focalLengthMm(), base.wavelengthNm(), maxNm);

        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("apertureDiameterMm", fmt(base.apertureDiameterMm()));
        snapshot.put("focalLengthMm", fmt(base.focalLengthMm()));
        snapshot.put("wavelengthNm", fmt(base.wavelengthNm()));
        snapshot.put("dpi", fmt(base.dpi()));
        snapshot.put("targetOffsetXmm", fmt(base.targetOffsetXmm()));
        snapshot.put("targetOffsetYmm", fmt(base.targetOffsetYmm()));
        snapshot.put("maskType", base.maskType().name());
        snapshot.put("polarity", base.polarity().name());
        snapshot.put("redNm", fmt(redNm));
        snapshot.put("greenNm", fmt(greenNm));
        snapshot.put("blueNm", fmt(blueNm));

        List<ValidationMetric> metrics = List.of(
                new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "CHANNEL_SPAN_NM", "Wavelength span", maxNm - minNm, "nm"),
                new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "FOCAL_SPREAD_MM", "Estimated focal spread", Math.abs(fMin - fMax), "mm"),
                new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXELS_PER_OUTER_ZONE", "Pixels per outer zone", m.pixelsPerOuterZone(), "px")
        );

        List<ValidationFinding> findings = new ArrayList<>();
        if ((maxNm - minNm) > 220.0) {
            findings.add(new ValidationFinding(
                    ValidationLayer.NUMERICAL_PROPAGATION,
                    "LARGE_CHANNEL_SPAN",
                    "Large RGB wavelength span may increase chromatic blur.",
                    ValidationSeverity.WARNING));
        }
        findings.add(notApplicableExperimentalFinding());

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "RGB channels are validated independently using one shared geometric base.", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Cross-channel coherence and sensor spectral response are not modeled.", true),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Manufacturing metrics use the base-zone geometry at design wavelength.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Experimental channel calibration hooks are placeholders only.", true)
        );

        return new DesignValidationReport(
                "rgb-zone-plate",
                stableHash(snapshot),
                snapshot,
                minNm,
                maxNm,
                base.apertureDiameterMm(),
                List.of(base.focalLengthMm()),
                Units.pixelSizeMm(base.dpi()) * 1000.0,
                assumptions,
                metrics,
                findings
        );
    }

    public static DesignValidationReport forMultiFocus(MultiFocusParameters p) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("apertureDiameterMm", fmt(p.apertureDiameterMm()));
        snapshot.put("wavelengthNm", fmt(p.wavelengthNm()));
        snapshot.put("dpi", fmt(p.dpi()));
        snapshot.put("maskType", p.maskType().name());
        snapshot.put("polarity", p.polarity().name());
        for (int i = 0; i < p.focusPoints().size(); i++) {
            MultiFocusParameters.FocusPoint fp = p.focusPoints().get(i);
            snapshot.put("fp[" + i + "].x", fmt(fp.xMm()));
            snapshot.put("fp[" + i + "].y", fmt(fp.yMm()));
            snapshot.put("fp[" + i + "].z", fmt(fp.zMm()));
        }

        List<Double> zValues = p.focusPoints().stream().map(MultiFocusParameters.FocusPoint::zMm).toList();
        double minZ = zValues.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maxZ = zValues.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        List<ValidationMetric> metrics = List.of(
                new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "FOCUS_POINT_COUNT", "Focus point count", p.focusPoints().size(), ""),
                new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "FOCUS_DEPTH_SPAN_MM", "Focus depth span", maxZ - minZ, "mm"),
                new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXEL_SIZE_UM", "Pixel size", Units.pixelSizeMm(p.dpi()) * 1000.0, "µm")
        );

        List<ValidationFinding> findings = List.of(
                new ValidationFinding(
                        ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "PRINTABILITY_PARTIAL",
                        "Detailed printability heuristics are not implemented for multi-focus splitting.",
                        ValidationSeverity.INFO),
                notApplicableExperimentalFinding()
        );

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "Focus points are treated as independent targets sharing one aperture.", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Interference between split sub-apertures is not fully simulated here.", true),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Manufacturing feasibility depends on local fringe density not modeled by this report.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Experimental PSF measurements are out of scope for deterministic validation.", true)
        );

        return new DesignValidationReport(
                "multi-focus",
                stableHash(snapshot),
                snapshot,
                p.wavelengthNm(),
                p.wavelengthNm(),
                p.apertureDiameterMm(),
                zValues,
                Units.pixelSizeMm(p.dpi()) * 1000.0,
                assumptions,
                metrics,
                findings
        );
    }

    public static DesignValidationReport forHexMacroCell(HexMacroCellParameters p) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("macroRadiusMm", fmt(p.macroRadiusMm()));
        snapshot.put("subDiameterMm", fmt(p.subDiameterMm()));
        snapshot.put("subPitchMm", fmt(p.subPitchMm()));
        snapshot.put("focalLengthMm", fmt(p.focalLengthMm()));
        snapshot.put("targetOffsetXmm", fmt(p.targetOffsetXmm()));
        snapshot.put("targetOffsetYmm", fmt(p.targetOffsetYmm()));
        snapshot.put("wavelengthNm", fmt(p.wavelengthNm()));
        snapshot.put("dpi", fmt(p.dpi()));
        snapshot.put("maskType", p.maskType().name());
        snapshot.put("polarity", p.polarity().name());

        List<ValidationMetric> metrics = new ArrayList<>();
        metrics.add(new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "SUB_ELEMENT_COUNT", "Sub-element count",
                HexMacroCellRenderer.countSubElements(p), ""));
        metrics.add(new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "PITCH_TO_DIAMETER", "Sub-pitch / sub-diameter",
                p.subPitchMm() / p.subDiameterMm(), ""));
        metrics.add(new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXEL_SIZE_UM", "Pixel size",
                Units.pixelSizeMm(p.dpi()) * 1000.0, "µm"));

        List<ValidationFinding> findings = new ArrayList<>();
        if (p.subPitchMm() < p.subDiameterMm()) {
            findings.add(new ValidationFinding(
                    ValidationLayer.MANUFACTURING_PRINTABILITY,
                    "SUB_ELEMENTS_OVERLAP",
                    "Sub-pitch is smaller than sub-diameter; neighboring sub-elements overlap.",
                    ValidationSeverity.WARNING));
        }
        findings.add(notApplicableExperimentalFinding());

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "Sub-elements are assumed to contribute coherently to one nominal focal target.", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Only aggregate geometric proxies are used; no full-field propagation is run.", true),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Local printer process effects are not modeled for each sub-element boundary.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "No measured macro-cell efficiency data is attached yet.", true)
        );

        return new DesignValidationReport(
                "hex-macro-cell",
                stableHash(snapshot),
                snapshot,
                p.wavelengthNm(),
                p.wavelengthNm(),
                2.0 * p.macroRadiusMm(),
                List.of(p.focalLengthMm()),
                Units.pixelSizeMm(p.dpi()) * 1000.0,
                assumptions,
                metrics,
                findings
        );
    }

    public static DesignValidationReport forWindowFoil(WindowFoilParameters p) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("sheetWidthMm", fmt(p.sheetWidthMm()));
        snapshot.put("sheetHeightMm", fmt(p.sheetHeightMm()));
        snapshot.put("macroRadiusMm", fmt(p.macroRadiusMm()));
        snapshot.put("subDiameterMm", fmt(p.subDiameterMm()));
        snapshot.put("subPitchMm", fmt(p.subPitchMm()));
        snapshot.put("wavelengthNm", fmt(p.wavelengthNm()));
        snapshot.put("dpi", fmt(p.dpi()));
        snapshot.put("maskType", p.maskType().name());
        snapshot.put("polarity", p.polarity().name());
        snapshot.put("drawCropMarks", String.valueOf(p.drawCropMarks()));
        for (int i = 0; i < p.cellSpecs().size(); i++) {
            WindowFoilParameters.CellSpec cs = p.cellSpecs().get(i);
            snapshot.put("cell[" + i + "].f", fmt(cs.focalLengthMm()));
            snapshot.put("cell[" + i + "].x", fmt(cs.targetOffsetXmm()));
            snapshot.put("cell[" + i + "].y", fmt(cs.targetOffsetYmm()));
        }

        List<Double> focalTargets = p.cellSpecs().isEmpty()
                ? List.of(p.defaultCellSpec().focalLengthMm())
                : p.cellSpecs().stream()
                        .map(WindowFoilParameters.CellSpec::focalLengthMm)
                        .distinct()
                        .toList();

        List<ValidationMetric> metrics = List.of(
                new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "CELL_COUNT", "Cell count",
                        WindowFoilRenderer.countCells(p), ""),
                new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "SHEET_AREA_MM2", "Sheet area",
                        p.sheetWidthMm() * p.sheetHeightMm(), "mm²"),
                new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXEL_SIZE_UM", "Pixel size",
                        Units.pixelSizeMm(p.dpi()) * 1000.0, "µm")
        );

        List<ValidationFinding> findings = new ArrayList<>();
        if (focalTargets.isEmpty()) {
            findings.add(new ValidationFinding(
                    ValidationLayer.ANALYTICAL_OPTICS,
                    "UNIFORM_DEFAULT_CELL_SPEC",
                    "No explicit cell specs supplied; default focal settings are implied.",
                    ValidationSeverity.INFO));
        }
        findings.add(notApplicableExperimentalFinding());

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "Window-foil analysis treats each tile as repetition of hex macro cells.", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Global field stitching effects across tile boundaries are not simulated.", true),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Printability depends on local printer calibration over large sheets.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Environmental and mounting tests are represented as hooks only.", true)
        );

        return new DesignValidationReport(
                "window-foil",
                stableHash(snapshot),
                snapshot,
                p.wavelengthNm(),
                p.wavelengthNm(),
                Math.max(p.sheetWidthMm(), p.sheetHeightMm()),
                focalTargets,
                Units.pixelSizeMm(p.dpi()) * 1000.0,
                assumptions,
                metrics,
                findings
        );
    }

    public static DesignValidationReport forHologram(int sidePx, int iterations, double dpi, double wavelengthNm) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("sidePx", String.valueOf(sidePx));
        snapshot.put("iterations", String.valueOf(iterations));
        snapshot.put("dpi", fmt(dpi));
        snapshot.put("wavelengthNm", fmt(wavelengthNm));

        double pixelUm = Units.pixelSizeMm(dpi) * 1000.0;
        double apertureMm = sidePx * Units.pixelSizeMm(dpi);

        List<ValidationMetric> metrics = List.of(
                new ValidationMetric(ValidationLayer.ANALYTICAL_OPTICS, "GRID_SIDE_PX", "Grid side", sidePx, "px"),
                new ValidationMetric(ValidationLayer.NUMERICAL_PROPAGATION, "GS_ITERATIONS", "GS iterations", iterations, ""),
                new ValidationMetric(ValidationLayer.MANUFACTURING_PRINTABILITY, "PIXEL_SIZE_UM", "Pixel size", pixelUm, "µm")
        );

        List<ValidationFinding> findings = new ArrayList<>();
        if (iterations < 20) {
            findings.add(new ValidationFinding(
                    ValidationLayer.NUMERICAL_PROPAGATION,
                    "LOW_ITERATION_COUNT",
                    "Low GS iteration count may reduce reconstruction quality.",
                    ValidationSeverity.WARNING));
        }
        findings.add(notApplicableExperimentalFinding());

        List<ValidationAssumption> assumptions = List.of(
                new ValidationAssumption(ValidationLayer.ANALYTICAL_OPTICS,
                        "Hologram validation is based on grid and phase-iteration metadata.", false),
                new ValidationAssumption(ValidationLayer.NUMERICAL_PROPAGATION,
                        "Convergence metrics are proxy-only and do not include detector noise.", true),
                new ValidationAssumption(ValidationLayer.MANUFACTURING_PRINTABILITY,
                        "Printer/material phase response is not measured in deterministic mode.", true),
                new ValidationAssumption(ValidationLayer.EXPERIMENTAL_HOOKS,
                        "Bench-top reconstruction measurements are exposed as future hooks.", true)
        );

        return new DesignValidationReport(
                "hologram",
                stableHash(snapshot),
                snapshot,
                wavelengthNm,
                wavelengthNm,
                apertureMm,
                List.of(),
                pixelUm,
                assumptions,
                metrics,
                findings
        );
    }

    private static ValidationFinding notApplicableExperimentalFinding() {
        return new ValidationFinding(
                ValidationLayer.EXPERIMENTAL_HOOKS,
                "EXPERIMENTAL_VALIDATION_PENDING",
                "Experimental validation hooks are defined, but no measured data is attached.",
                ValidationSeverity.INFO
        );
    }

    private static String stableHash(Map<String, String> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            snapshot.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '=');
                        digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    });
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable in current runtime; deterministic validation hash cannot be computed.", e);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.12g", v);
    }
}

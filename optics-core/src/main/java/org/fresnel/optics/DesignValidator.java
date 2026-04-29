package org.fresnel.optics;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes design metrics and printability warnings for a Fresnel zone plate.
 *
 * <p>Outermost zone width approximation: {@code Δr ≈ λ·f / D}.
 *
 * <p>Printability thresholds (printer pixels per outermost zone):
 * <ul>
 *     <li>&lt; 2 px: critical (ERROR)</li>
 *     <li>&lt; 5 px: suboptimal (WARNING)</li>
 *     <li>≥ 5 px: good (no warning)</li>
 * </ul>
 */
public final class DesignValidator {

    /** Average transmission of a binary amplitude zone plate. */
    public static final double BINARY_AMPLITUDE_TRANSMISSION = 0.5;
    /** First-order diffraction efficiency of a binary amplitude zone plate (1/π² ≈ 0.1013). */
    public static final double BINARY_AMPLITUDE_FIRST_ORDER_EFFICIENCY = 1.0 / (Math.PI * Math.PI);

    private DesignValidator() {}

    /** Compute metrics for a single zone plate design. */
    public static DesignMetrics computeMetrics(SingleZonePlateParameters p) {
        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double outerZoneMm = lambdaMm * p.focalLengthMm() / p.apertureDiameterMm();
        double printerPixelMm = Units.pixelSizeMm(p.dpi());
        double pixelsPerOuterZone = outerZoneMm / printerPixelMm;
        // Number of zones across the radius:  n = R^2 / (λ·f)
        double radiusMm = p.apertureDiameterMm() / 2.0;
        int numberOfZones = (int) Math.floor((radiusMm * radiusMm) / (lambdaMm * p.focalLengthMm()));

        // Chromatic shift table at sample R/G/B wavelengths (paraxial f ∝ 1/λ).
        java.util.List<DesignMetrics.ChromaticShift> chromatic = java.util.List.of(
                new DesignMetrics.ChromaticShift(450.0,
                        focalLengthAtWavelength(p.focalLengthMm(), p.wavelengthNm(), 450.0)),
                new DesignMetrics.ChromaticShift(532.0,
                        focalLengthAtWavelength(p.focalLengthMm(), p.wavelengthNm(), 532.0)),
                new DesignMetrics.ChromaticShift(p.wavelengthNm(), p.focalLengthMm()),
                new DesignMetrics.ChromaticShift(630.0,
                        focalLengthAtWavelength(p.focalLengthMm(), p.wavelengthNm(), 630.0)));

        // Defocus blur table at a few wall distances scaled to the design focal length.
        double f = p.focalLengthMm();
        double[] sampleDistances = { 0.5 * f, 0.75 * f, f, 1.5 * f, 2.0 * f };
        java.util.List<DesignMetrics.DefocusEntry> defocus = new ArrayList<>(sampleDistances.length);
        for (double z : sampleDistances) {
            defocus.add(new DesignMetrics.DefocusEntry(z,
                    defocusBlurMm(p.apertureDiameterMm(), f, z)));
        }

        return new DesignMetrics(
                outerZoneMm * 1000.0,
                printerPixelMm * 1000.0,
                pixelsPerOuterZone,
                BINARY_AMPLITUDE_TRANSMISSION,
                BINARY_AMPLITUDE_FIRST_ORDER_EFFICIENCY,
                numberOfZones,
                chromatic,
                defocus);
    }

    /** Validate a single zone plate design and produce warnings. */
    public static ValidationResult validate(SingleZonePlateParameters p) {
        DesignMetrics m = computeMetrics(p);
        List<ValidationResult.Warning> warnings = new ArrayList<>();
        boolean valid = true;
        if (m.pixelsPerOuterZone() < 2.0) {
            warnings.add(new ValidationResult.Warning(
                    "OUTER_ZONE_TOO_SMALL",
                    String.format(
                            "The outer zone width is only %.2f printer pixels (need ≥ 2).",
                            m.pixelsPerOuterZone()),
                    ValidationResult.Warning.Severity.ERROR));
            valid = false;
        } else if (m.pixelsPerOuterZone() < 5.0) {
            warnings.add(new ValidationResult.Warning(
                    "OUTER_ZONE_SUBOPTIMAL",
                    String.format(
                            "The outer zone covers %.2f printer pixels; ≥ 5 px is recommended for good print fidelity.",
                            m.pixelsPerOuterZone()),
                    ValidationResult.Warning.Severity.WARNING));
        }
        if (m.numberOfZones() < 5) {
            warnings.add(new ValidationResult.Warning(
                    "FEW_ZONES",
                    String.format(
                            "Only %d Fresnel zones fit in the aperture; focus quality will be poor.",
                            m.numberOfZones()),
                    ValidationResult.Warning.Severity.WARNING));
        }
        return new ValidationResult(valid, warnings, m);
    }

    /**
     * Estimate the focal length at a different wavelength (paraxial diffractive approximation,
     * f(λ) ∝ 1/λ).
     */
    public static double focalLengthAtWavelength(double designFocalMm, double designWavelengthNm,
                                                 double otherWavelengthNm) {
        if (otherWavelengthNm <= 0) throw new IllegalArgumentException("wavelength must be > 0");
        return designFocalMm * (designWavelengthNm / otherWavelengthNm);
    }

    /**
     * Defocus blur diameter for a wall at distance {@code zMm} when the design focal length is
     * {@code fMm} and the aperture is {@code dMm}: {@code c ≈ D·|f-z|/f}.
     */
    public static double defocusBlurMm(double apertureDiameterMm, double focalLengthMm, double wallDistanceMm) {
        return apertureDiameterMm * Math.abs(focalLengthMm - wallDistanceMm) / focalLengthMm;
    }
}

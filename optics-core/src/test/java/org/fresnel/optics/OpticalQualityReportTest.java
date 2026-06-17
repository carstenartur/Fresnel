package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpticalQualityReportTest {

    // Reference parameters: λ = 550 nm, f = 1000 mm, D = 10 mm
    // F# = 100, NA = 0.005
    private static final SingleZonePlateParameters P =
            SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);

    @Test
    void basicFieldsMatchInputParameters() {
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        assertEquals(550.0, r.wavelengthNm(),         1e-9);
        assertEquals(1000.0, r.focalLengthMm(),       1e-9);
        assertEquals(10.0, r.apertureDiameterMm(),    1e-9);
    }

    @Test
    void numericalApertureFormula() {
        // NA = D / (2·f) = 10 / 2000 = 0.005
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        assertEquals(0.005, r.numericalAperture(), 1e-9);
    }

    @Test
    void fNumberFormula() {
        // F# = f / D = 1000 / 10 = 100
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        assertEquals(100.0, r.fNumber(), 1e-9);
    }

    @Test
    void airyDiskDiameterFormula() {
        // d_Airy = 2.44·λ·F# = 2.44 × 550e-6 mm × 100 × 1000 µm/mm
        //        = 2.44 × 0.055 mm × 1000 = 134.2 µm
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        double expected = 2.44 * (550e-6) * 100.0 * 1000.0; // µm
        assertEquals(expected, r.airyDiskDiameterMicrons(), 1e-6);
        assertEquals(134.2, r.airyDiskDiameterMicrons(), 0.01);
    }

    @Test
    void rayleighAngularResolutionFormula() {
        // θ_R = 1.22·λ/D = 1.22 × 550e-6 mm / 10 mm = 6.71e-5 rad
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        double expected = 1.22 * 550e-6 / 10.0;
        assertEquals(expected, r.rayleighAngularResolutionRad(), 1e-12);
    }

    @Test
    void depthOfFocusFormula() {
        // DoF = 2·λ·F#² = 2 × 550e-6 mm × 10000 × 1000 µm/mm = 11 000 µm
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        double expected = 2.0 * 550e-6 * 100.0 * 100.0 * 1000.0; // µm
        assertEquals(expected, r.depthOfFocusMicrons(), 1e-6);
        assertEquals(11000.0, r.depthOfFocusMicrons(), 0.01);
    }

    @Test
    void outermostZoneWidthMatchesDesignMetrics() {
        // Δr = λ·f/D = 550e-6 mm × 1000/10 × 1000 µm/mm = 55 µm
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        DesignMetrics m = DesignValidator.computeMetrics(P);
        assertEquals(m.outerZoneWidthMicrons(), r.outermostZoneWidthMicrons(), 1e-9);
        assertEquals(55.0, r.outermostZoneWidthMicrons(), 1e-6);
    }

    @Test
    void chromaticFocalShiftDefaultRange() {
        // Default range 450–650 nm, design λ = 550 nm, f = 1000 mm
        // Δf = 1000 × 550 × (1/450 − 1/650) = 550000 × (0.002222 − 0.001538)
        //    = 550000 × 0.000684 ≈ 376.07 mm
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P);
        double expected = 1000.0 * 550.0 * (1.0 / 450.0 - 1.0 / 650.0);
        assertEquals(expected, r.chromaticFocalShiftMm(), 1e-6);
        assertEquals(450.0, r.chromaticRangeMinNm(), 1e-9);
        assertEquals(650.0, r.chromaticRangeMaxNm(), 1e-9);
    }

    @Test
    void chromaticFocalShiftCustomRange() {
        // Range 500–600 nm
        OpticalQualityReport r = DesignValidator.computeOpticalQualityReport(P, 500.0, 600.0);
        double expected = 1000.0 * 550.0 * (1.0 / 500.0 - 1.0 / 600.0);
        assertEquals(expected, r.chromaticFocalShiftMm(), 1e-6);
        assertEquals(500.0, r.chromaticRangeMinNm(), 1e-9);
        assertEquals(600.0, r.chromaticRangeMaxNm(), 1e-9);
    }

    @Test
    void invalidChromaticRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> DesignValidator.computeOpticalQualityReport(P, 650.0, 450.0));
        assertThrows(IllegalArgumentException.class,
                () -> DesignValidator.computeOpticalQualityReport(P, -1.0, 650.0));
        assertThrows(IllegalArgumentException.class,
                () -> DesignValidator.computeOpticalQualityReport(P, 450.0, 450.0));
    }
}

package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignValidatorTest {

    @Test
    void outerZoneWidthMatchesAnalyticFormula() {
        // λ = 550 nm, f = 100 mm, D = 20 mm  =>  Δr = λf/D = 2.75 µm
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(20.0, 100.0, 550.0, 2400.0);
        DesignMetrics m = DesignValidator.computeMetrics(p);
        assertEquals(2.75, m.outerZoneWidthMicrons(), 1e-3);
        assertEquals(10.5833, m.printerPixelMicrons(), 1e-3);
        // 2.75 / 10.583 ≈ 0.26
        assertEquals(0.26, m.pixelsPerOuterZone(), 0.01);
    }

    @Test
    void warnsWhenOuterZoneIsSmallerThanTwoPixels() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(20.0, 100.0, 550.0, 2400.0);
        ValidationResult v = DesignValidator.validate(p);
        assertFalse(v.valid());
        assertTrue(v.warnings().stream()
                .anyMatch(w -> w.code().equals("OUTER_ZONE_TOO_SMALL")
                        && w.severity() == ValidationResult.Warning.Severity.ERROR));
    }

    @Test
    void wellPrintableDesignHasNoErrors() {
        // λ = 550 nm, D = 20 mm, f = 5000 mm => Δr = 137.5 µm => ~13 px @ 2400 dpi
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(20.0, 5000.0, 550.0, 2400.0);
        ValidationResult v = DesignValidator.validate(p);
        assertTrue(v.valid(), () -> "expected valid, got warnings " + v.warnings());
        assertEquals(13.0, v.metrics().pixelsPerOuterZone(), 0.5);
        assertTrue(v.warnings().stream()
                .noneMatch(w -> w.severity() == ValidationResult.Warning.Severity.ERROR));
    }

    @Test
    void chromaticFocalShift() {
        // f(λ) ∝ 1/λ
        double f550 = 5000.0;
        double f650 = DesignValidator.focalLengthAtWavelength(f550, 550.0, 650.0);
        double f450 = DesignValidator.focalLengthAtWavelength(f550, 550.0, 450.0);
        assertEquals(4230.77, f650, 0.5);
        assertEquals(6111.11, f450, 0.5);
    }

    @Test
    void metricsRetainDoublePrecision() {
        // The optical math must run in double precision (not float). For these
        // parameters the analytical outer-zone width is exactly λ·f/D = 2.75e-3 mm.
        // float (~7 significant digits) would only match to ~1e-7 here; double
        // matches to ~1e-12 or better. This guards against accidental
        // float/double downcasts in the metrics pipeline.
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(20.0, 100.0, 550.0, 2400.0);
        DesignMetrics m = DesignValidator.computeMetrics(p);
        double expectedMicrons = (550.0e-6 * 100.0 / 20.0) * 1000.0; // = 2.75 µm
        assertEquals(expectedMicrons, m.outerZoneWidthMicrons(), 1.0e-12,
                "outer-zone width must retain double precision");
    }

    @Test
    void defocusBlurFormula() {
        // D=20 mm, f=5000 mm, z=3000 mm  =>  c = 20·|5000-3000|/5000 = 8 mm
        assertEquals(8.0, DesignValidator.defocusBlurMm(20.0, 5000.0, 3000.0), 1e-9);
        assertEquals(0.0, DesignValidator.defocusBlurMm(20.0, 5000.0, 5000.0), 1e-9);
    }
}

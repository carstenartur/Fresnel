package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignValidationReportsTest {

    @Test
    void zonePlateReportContainsAnalyticalLayer() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);
        DesignValidationReport r = DesignValidationReports.forZonePlate(p);
        assertTrue(r.metrics().stream().anyMatch(m -> m.layer() == ValidationLayer.ANALYTICAL_OPTICS));
    }

    @Test
    void rgbReportContainsNumericalLayer() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);
        DesignValidationReport r = DesignValidationReports.forRgbZonePlate(p, 630.0, 532.0, 450.0);
        assertTrue(r.metrics().stream().anyMatch(m -> m.layer() == ValidationLayer.NUMERICAL_PROPAGATION));
    }

    @Test
    void multiFocusReportContainsManufacturingLayer() {
        MultiFocusParameters p = new MultiFocusParameters(
                10.0,
                java.util.List.of(
                        new MultiFocusParameters.FocusPoint(-2.0, 0.0, 900.0),
                        new MultiFocusParameters.FocusPoint(2.0, 0.0, 1100.0)),
                550.0,
                1200.0,
                MaskType.BINARY_AMPLITUDE,
                Polarity.POSITIVE);
        DesignValidationReport r = DesignValidationReports.forMultiFocus(p);
        assertTrue(r.metrics().stream().anyMatch(m -> m.layer() == ValidationLayer.MANUFACTURING_PRINTABILITY));
    }

    @Test
    void everyReportContainsExperimentalHooksLayer() {
        SingleZonePlateParameters zp = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);

        assertTrue(findingsLayers(DesignValidationReports.forZonePlate(zp))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "zone-plate");

        assertTrue(findingsLayers(DesignValidationReports.forRgbZonePlate(zp, 630.0, 532.0, 450.0))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "rgb-zone-plate");

        MultiFocusParameters mfp = new MultiFocusParameters(
                10.0,
                java.util.List.of(
                        new MultiFocusParameters.FocusPoint(-2.0, 0.0, 900.0),
                        new MultiFocusParameters.FocusPoint(2.0, 0.0, 1100.0)),
                550.0, 1200.0, MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        assertTrue(findingsLayers(DesignValidationReports.forMultiFocus(mfp))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "multi-focus");

        HexMacroCellParameters hex = HexMacroCellParameters.onAxis(30.0, 10.0, 11.0, 1000.0, 550.0, 600.0);
        assertTrue(findingsLayers(DesignValidationReports.forHexMacroCell(hex))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "hex-macro-cell");

        WindowFoilParameters foil = new WindowFoilParameters(
                200.0, 100.0, 25.0, 8.0, 9.0, 550.0, 150.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE, java.util.List.of(), false);
        assertTrue(findingsLayers(DesignValidationReports.forWindowFoil(foil))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "window-foil");

        assertTrue(findingsLayers(DesignValidationReports.forHologram(128, 40, 600.0, 550.0))
                .contains(ValidationLayer.EXPERIMENTAL_HOOKS), "hologram");
    }

    private static Set<ValidationLayer> findingsLayers(DesignValidationReport r) {
        return r.findings().stream().map(ValidationFinding::layer).collect(Collectors.toSet());
    }

    @Test
    void parameterHashIsStableForSameInput() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 1200.0);
        String h1 = DesignValidationReports.forZonePlate(p).parameterHash();
        String h2 = DesignValidationReports.forZonePlate(p).parameterHash();
        assertEquals(h1, h2);
    }
}

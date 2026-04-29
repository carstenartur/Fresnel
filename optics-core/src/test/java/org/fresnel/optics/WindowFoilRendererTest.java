package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowFoilRendererTest {

    @Test
    void rendersSheetOfExpectedSize() {
        // 100 mm × 50 mm at 150 dpi → 25.4/150 ≈ 0.169 mm/px → ~590 × 295
        WindowFoilParameters p = new WindowFoilParameters(
                100.0, 50.0, 15.0, 4.0, 4.0, 550.0, 150.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(1000.0)),
                false);
        RenderResult r = WindowFoilRenderer.render(p);
        BufferedImage img = r.image();
        assertTrue(img.getWidth() >= 585 && img.getWidth() <= 600,
                "unexpected width: " + img.getWidth());
        assertTrue(img.getHeight() >= 290 && img.getHeight() <= 305);
    }

    @Test
    void containsAtLeastOneCell() {
        WindowFoilParameters p = new WindowFoilParameters(
                200.0, 200.0, 30.0, 5.0, 5.0, 550.0, 100.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(2000.0)),
                false);
        int n = WindowFoilRenderer.countCells(p);
        assertTrue(n >= 4, "expected ≥ 4 cells, got " + n);
    }

    @Test
    void differentCellSpecsCycle() {
        WindowFoilParameters p = new WindowFoilParameters(
                300.0, 300.0, 50.0, 5.0, 5.0, 550.0, 80.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(1000.0),
                        WindowFoilParameters.CellSpec.onAxis(2000.0),
                        WindowFoilParameters.CellSpec.onAxis(3000.0)),
                false);
        assertEquals(1000.0, p.specForCell(0).focalLengthMm());
        assertEquals(2000.0, p.specForCell(1).focalLengthMm());
        assertEquals(3000.0, p.specForCell(2).focalLengthMm());
        assertEquals(1000.0, p.specForCell(3).focalLengthMm());
        assertEquals(2000.0, p.specForCell(4).focalLengthMm());
    }

    @Test
    void cropMarksAreDrawn() {
        WindowFoilParameters p = new WindowFoilParameters(
                100.0, 100.0, 20.0, 4.0, 4.0, 550.0, 100.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(1000.0)),
                true);
        RenderResult r = WindowFoilRenderer.render(p);
        BufferedImage img = r.image();
        // Top-left corner pixel should be set by the crop mark.
        assertEquals(255, img.getRaster().getSample(0, 0, 0));
        // And the corresponding pixel in the no-crop-mark version is 0 (outside any cell).
        WindowFoilParameters p2 = new WindowFoilParameters(
                p.sheetWidthMm(), p.sheetHeightMm(), p.macroRadiusMm(), p.subDiameterMm(),
                p.subPitchMm(), p.wavelengthNm(), p.dpi(), p.maskType(), p.polarity(),
                p.cellSpecs(), false);
        RenderResult r2 = WindowFoilRenderer.render(p2);
        assertEquals(0, r2.image().getRaster().getSample(0, 0, 0));
    }
}

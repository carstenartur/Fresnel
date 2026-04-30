package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HexMacroCellRendererTest {

    @Test
    void rendersImageOfExpectedSize() {
        // 30 mm cell at 600 dpi → 25.4/600 ≈ 0.0423 mm/px → ~709 px
        HexMacroCellParameters p = HexMacroCellParameters.onAxis(
                15.0, 5.0, 5.0, 1000.0, 550.0, 600.0);
        RenderResult r = HexMacroCellRenderer.render(p);
        BufferedImage img = r.image();
        assertEquals(img.getWidth(), img.getHeight());
        assertTrue(img.getWidth() >= 700 && img.getWidth() <= 720,
                "unexpected size: " + img.getWidth());
        assertEquals(1, img.getWidth() % 2);
    }

    @Test
    void cornersOutsideHexAreBlack() {
        HexMacroCellParameters p = HexMacroCellParameters.onAxis(
                10.0, 4.0, 4.5, 500.0, 550.0, 300.0);
        RenderResult r = HexMacroCellRenderer.render(p);
        BufferedImage img = r.image();
        // The four image corners are outside the inscribed hex.
        assertEquals(0, img.getRaster().getSample(0, 0, 0));
        assertEquals(0, img.getRaster().getSample(img.getWidth() - 1, 0, 0));
        assertEquals(0, img.getRaster().getSample(0, img.getHeight() - 1, 0));
        assertEquals(0, img.getRaster().getSample(img.getWidth() - 1, img.getHeight() - 1, 0));
    }

    @Test
    void containsAtLeastOneSubElement() {
        HexMacroCellParameters p = HexMacroCellParameters.onAxis(
                15.0, 5.0, 5.0, 500.0, 550.0, 300.0);
        int n = HexMacroCellRenderer.countSubElements(p);
        assertTrue(n >= 7, "expected ≥ 7 sub-elements, got " + n);
        RenderResult r = HexMacroCellRenderer.render(p);
        // Should contain both bright and dark pixels (the zone plates).
        BufferedImage img = r.image();
        boolean sawWhite = false, sawBlack = false;
        int cx = img.getWidth() / 2;
        for (int y = cx - 50; y <= cx + 50; y++) {
            for (int x = cx - 50; x <= cx + 50; x++) {
                int v = img.getRaster().getSample(x, y, 0);
                if (v == 0) sawBlack = true;
                if (v == 255) sawWhite = true;
            }
        }
        assertTrue(sawBlack && sawWhite, "expected mixed pixels in central area");
    }

    @Test
    void hexLatticeCentresAreInsideHex() {
        List<double[]> centres = HexMacroCellRenderer.hexLatticeCentresInsideHex(20.0, 5.0);
        double R = 20.0;
        double sqrt3 = Math.sqrt(3.0);
        double halfFlat = R * sqrt3 / 2.0;
        double invSqrt3 = 1.0 / sqrt3;
        for (double[] c : centres) {
            assertTrue(Math.abs(c[1]) <= halfFlat + 1e-6,
                    "centre " + c[0] + "," + c[1] + " outside hex (y)");
            assertTrue(Math.abs(c[0]) + Math.abs(c[1]) * invSqrt3 <= R + 1e-6,
                    "centre " + c[0] + "," + c[1] + " outside hex");
        }
        assertTrue(centres.size() > 10);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> HexMacroCellParameters.onAxis(-1.0, 5.0, 5.0, 1000.0, 550.0, 600.0));
        assertThrows(IllegalArgumentException.class,
                () -> HexMacroCellParameters.onAxis(15.0, 5.0, 4.0, 1000.0, 550.0, 600.0)); // pitch < diameter
        assertThrows(IllegalArgumentException.class,
                () -> HexMacroCellParameters.onAxis(2.0, 5.0, 5.0, 1000.0, 550.0, 600.0)); // sub > 2R
    }
}

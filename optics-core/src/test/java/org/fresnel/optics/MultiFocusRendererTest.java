package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiFocusRendererTest {

    @Test
    void rendersAndProducesMixedPixels() {
        MultiFocusParameters p = new MultiFocusParameters(
                10.0,
                List.of(
                        new MultiFocusParameters.FocusPoint(-2.0, 0.0, 1000.0),
                        new MultiFocusParameters.FocusPoint(+2.0, 0.0, 1000.0)),
                550.0, 600.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        RenderResult r = MultiFocusRenderer.render(p);
        BufferedImage img = r.image();
        assertEquals(img.getWidth(), img.getHeight());
        boolean sawWhite = false, sawBlack = false;
        int cx = img.getWidth() / 2;
        for (int y = cx - 30; y <= cx + 30; y++) {
            for (int x = cx - 30; x <= cx + 30; x++) {
                int v = img.getRaster().getSample(x, y, 0);
                if (v == 0) sawBlack = true;
                if (v == 255) sawWhite = true;
            }
        }
        assertTrue(sawBlack && sawWhite);
    }

    @Test
    void hashAssignsApproximatelyEvenly() {
        // ~uniform distribution check
        int n = 5;
        int[] counts = new int[n];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                counts[MultiFocusRenderer.pixelToFocusIndex(x, y, n)]++;
            }
        }
        int total = 200 * 200;
        for (int c : counts) {
            double ratio = (double) c / total;
            assertTrue(ratio > 0.15 && ratio < 0.25,
                    "uneven distribution: " + ratio);
        }
    }

    @Test
    void lineOfPointsLinearlyInterpolates() {
        var pts = MultiFocusParameters.lineOfPoints(0, 0, 1000, 10, 0, 1000, 11);
        assertEquals(11, pts.size());
        assertEquals(0.0, pts.get(0).xMm(), 1e-9);
        assertEquals(10.0, pts.get(10).xMm(), 1e-9);
        assertEquals(5.0, pts.get(5).xMm(), 1e-9);
    }

    @Test
    void rejectsEmptyFocusPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiFocusParameters(10.0, List.of(), 550.0, 600.0,
                        MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE));
    }
}

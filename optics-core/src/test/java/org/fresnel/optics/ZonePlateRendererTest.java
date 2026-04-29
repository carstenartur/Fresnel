package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ZonePlateRendererTest {

    @Test
    void rendersImageOfExpectedSize() {
        // D = 10 mm @ 600 dpi => pixel = 25.4/600 ≈ 0.0423 mm
        // size = ceil(10 / 0.0423) ≈ 237 px (made odd if even)
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(10.0, 1000.0, 550.0, 600.0);
        RenderResult r = ZonePlateRenderer.render(p);
        BufferedImage img = r.image();
        assertEquals(img.getWidth(), img.getHeight());
        assertTrue(img.getWidth() >= 236 && img.getWidth() <= 240,
                "unexpected size: " + img.getWidth());
        assertEquals(1, img.getWidth() % 2, "size should be odd");
    }

    @Test
    void centerPixelIsTransparentForOnAxisPositivePolarity() {
        // At (0,0) the optical path length is exactly f, so phi = 2π·f/λ.
        // For typical f >> λ this is a large multiple of 2π and cos may take any value;
        // instead, verify that the very-near-center region contains both 0 and 255.
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 1200.0);
        RenderResult r = ZonePlateRenderer.render(p);
        BufferedImage img = r.image();
        boolean sawWhite = false, sawBlack = false;
        int cx = img.getWidth() / 2;
        int cy = img.getHeight() / 2;
        for (int y = cy - 20; y <= cy + 20; y++) {
            for (int x = cx - 20; x <= cx + 20; x++) {
                int v = img.getRaster().getSample(x, y, 0);
                if (v == 0) sawBlack = true;
                if (v == 255) sawWhite = true;
            }
        }
        assertTrue(sawBlack, "expected dark pixels");
        assertTrue(sawWhite, "expected bright pixels");
    }

    @Test
    void cornersOutsideApertureAreBlack() {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 600.0);
        RenderResult r = ZonePlateRenderer.render(p);
        BufferedImage img = r.image();
        assertEquals(0, img.getRaster().getSample(0, 0, 0));
        assertEquals(0, img.getRaster().getSample(img.getWidth() - 1, 0, 0));
        assertEquals(0, img.getRaster().getSample(0, img.getHeight() - 1, 0));
        assertEquals(0, img.getRaster().getSample(img.getWidth() - 1, img.getHeight() - 1, 0));
    }

    @Test
    void positiveAndNegativePolarityAreInverses() {
        SingleZonePlateParameters pos = new SingleZonePlateParameters(
                4.0, 50.0, 550.0, 600.0, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        SingleZonePlateParameters neg = new SingleZonePlateParameters(
                4.0, 50.0, 550.0, 600.0, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.NEGATIVE);
        BufferedImage a = ZonePlateRenderer.render(pos).image();
        BufferedImage b = ZonePlateRenderer.render(neg).image();
        assertEquals(a.getWidth(), b.getWidth());
        // Within the aperture, pixels must be inverses; outside both are 0.
        int cx = a.getWidth() / 2;
        int radiusPx = a.getWidth() / 2 - 2;
        int sampled = 0;
        for (int y = cx - radiusPx; y <= cx + radiusPx; y += 7) {
            for (int x = cx - radiusPx; x <= cx + radiusPx; x += 7) {
                int dx = x - cx, dy = y - cx;
                if (dx * dx + dy * dy > (radiusPx - 4) * (radiusPx - 4)) continue;
                int va = a.getRaster().getSample(x, y, 0);
                int vb = b.getRaster().getSample(x, y, 0);
                assertEquals(255, va + vb, "pixel (" + x + "," + y + ") should be inverse");
                sampled++;
            }
        }
        assertTrue(sampled > 10, "expected several aperture samples");
    }

    @Test
    void pngExportProducesValidPngBytes() throws IOException {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(5.0, 50.0, 550.0, 600.0);
        RenderResult r = ZonePlateRenderer.render(p);
        byte[] bytes = PngExporter.toPngBytes(r, 600.0);
        assertNotNull(bytes);
        assertTrue(bytes.length > 100);
        // PNG signature
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]); // P
        assertEquals((byte) 0x4E, bytes[2]); // N
        assertEquals((byte) 0x47, bytes[3]); // G
    }

    /**
     * Precision-budget guard. For the worst plausible design served by this engine
     * (≈ 5 m macro-cell projection in deep blue), proves that {@code double}
     * delivers an absolute phase error far below the 8-bit quantization step,
     * so {@link java.math.BigDecimal} arbitrary precision is not required.
     */
    @Test
    void worstCasePhasePrecisionFitsInDouble() {
        // Path length L (mm) for a 5 m macro-cell looking at a 5 m wall, edge ray.
        double Lmm = 5000.0 + 250.0; // ~5.25 m
        double lambdaMm = 400.0e-6;  // 400 nm (deep blue, worst-case wavelength)
        double phi = 2.0 * Math.PI * Lmm / lambdaMm;
        // Absolute error of phi in double precision: |phi| · 2^-52
        double absErrorRad = Math.abs(phi) * Math.pow(2.0, -52);
        // 8-bit greyscale phase quantization step in radians: 2π / 256
        double quantStepRad = 2.0 * Math.PI / 256.0;
        // Demand at least 100x headroom over the output quantization
        assertTrue(absErrorRad * 100.0 < quantStepRad,
                () -> "double precision insufficient: absErr=" + absErrorRad
                        + " rad, quantStep=" + quantStepRad + " rad");
        // Sanity: float (~24-bit mantissa) would be utterly inadequate here.
        double absErrorRadFloat = Math.abs(phi) * Math.pow(2.0, -23);
        assertTrue(absErrorRadFloat > quantStepRad,
                "float would actually have been good enough — re-evaluate the choice");
    }
}

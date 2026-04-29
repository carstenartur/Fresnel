package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Renders a single zone-plate design at three wavelengths (R / G / B) and combines
 * them into one RGB image. Useful for visualising chromatic behaviour and for
 * fabricating colour-separated overlays.
 *
 * <p>The same geometric parameters (aperture, focal length, DPI, off-axis target)
 * are reused for all three channels; only {@code wavelengthNm} varies. The output
 * is always {@code TYPE_INT_RGB}.
 */
public final class RgbZonePlateRenderer {

    private RgbZonePlateRenderer() {}

    /**
     * Render an RGB zone-plate composite.
     *
     * @param base     base parameters; its wavelength is ignored for the channels
     * @param redNm    red channel design wavelength, nm (typical 630)
     * @param greenNm  green channel design wavelength, nm (typical 532)
     * @param blueNm   blue channel design wavelength, nm (typical 450)
     */
    public static RenderResult render(SingleZonePlateParameters base,
                                      double redNm, double greenNm, double blueNm) {
        if (redNm <= 0 || greenNm <= 0 || blueNm <= 0)
            throw new IllegalArgumentException("wavelengths must be > 0");
        RenderResult r = ZonePlateRenderer.render(withWavelength(base, redNm));
        RenderResult g = ZonePlateRenderer.render(withWavelength(base, greenNm));
        RenderResult b = ZonePlateRenderer.render(withWavelength(base, blueNm));
        BufferedImage rImg = r.image();
        BufferedImage gImg = g.image();
        BufferedImage bImg = b.image();
        int w = rImg.getWidth();
        int h = rImg.getHeight();
        if (gImg.getWidth() != w || bImg.getWidth() != w
                || gImg.getHeight() != h || bImg.getHeight() != h) {
            throw new IllegalStateException("channel sizes differ — should not happen");
        }
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        WritableRaster rrR = rImg.getRaster();
        WritableRaster rrG = gImg.getRaster();
        WritableRaster rrB = bImg.getRaster();
        int[] rRow = new int[w];
        int[] gRow = new int[w];
        int[] bRow = new int[w];
        int[] outRow = new int[w];
        for (int y = 0; y < h; y++) {
            rrR.getSamples(0, y, w, 1, 0, rRow);
            rrG.getSamples(0, y, w, 1, 0, gRow);
            rrB.getSamples(0, y, w, 1, 0, bRow);
            for (int x = 0; x < w; x++) {
                outRow[x] = (rRow[x] << 16) | (gRow[x] << 8) | bRow[x];
            }
            rgb.setRGB(0, y, w, 1, outRow, 0, w);
        }
        return new RenderResult(rgb, r.pixelSizeMm());
    }

    private static SingleZonePlateParameters withWavelength(SingleZonePlateParameters p, double nm) {
        return new SingleZonePlateParameters(
                p.apertureDiameterMm(), p.focalLengthMm(), nm, p.dpi(),
                p.targetOffsetXmm(), p.targetOffsetYmm(),
                p.maskType(), p.polarity());
    }
}

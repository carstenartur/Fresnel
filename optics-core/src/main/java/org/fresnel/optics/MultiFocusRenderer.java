package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;

/**
 * Renders a multi-point / line focus mask via deterministic per-pixel sub-aperture
 * allocation: each pixel is assigned to exactly one focus by a stable hash, and is
 * then rendered as that focus' single zone-plate phase. The resulting mask, when
 * illuminated, produces N distinct focal spots whose intensities are roughly equal.
 *
 * <p>Per-pixel hashing instead of contiguous sub-apertures keeps every focal spot
 * spatially coherent across the entire aperture (preserving angular resolution per
 * spot) at the cost of introducing slight speckle, which is the standard trade-off
 * for this style of multi-focus diffractive optic.
 */
public final class MultiFocusRenderer {

    private MultiFocusRenderer() {}

    public static RenderResult render(MultiFocusParameters p) {
        double pixelSizeMm = Units.pixelSizeMm(p.dpi());
        int sizePx = Math.max(2, (int) Math.ceil(p.apertureDiameterMm() / pixelSizeMm));
        if (sizePx % 2 == 0) sizePx++;

        double radiusMm = p.apertureDiameterMm() / 2.0;
        double radiusPx = radiusMm / pixelSizeMm;
        double radiusPxSq = radiusPx * radiusPx;
        double centerPx = (sizePx - 1) / 2.0;
        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double k = 2.0 * Math.PI / lambdaMm;
        boolean binary = p.maskType() == MaskType.BINARY_AMPLITUDE;
        boolean positive = p.polarity() == Polarity.POSITIVE;
        List<MultiFocusParameters.FocusPoint> foci = p.focusPoints();
        int n = foci.size();

        // Pre-extract focus arrays for inner-loop speed.
        double[] xf = new double[n];
        double[] yf = new double[n];
        double[] zfSq = new double[n];
        for (int i = 0; i < n; i++) {
            MultiFocusParameters.FocusPoint f = foci.get(i);
            xf[i] = f.xMm();
            yf[i] = f.yMm();
            zfSq[i] = f.zMm() * f.zMm();
        }

        BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = img.getRaster();
        int[] row = new int[sizePx];

        for (int yPx = 0; yPx < sizePx; yPx++) {
            double dy = yPx - centerPx;
            double yMm = dy * pixelSizeMm;
            for (int xPx = 0; xPx < sizePx; xPx++) {
                double dx = xPx - centerPx;
                if (dx * dx + dy * dy > radiusPxSq) {
                    row[xPx] = 0;
                    continue;
                }
                double xMm = dx * pixelSizeMm;
                int idx = pixelToFocusIndex(xPx, yPx, n);
                double dxF = xMm - xf[idx];
                double dyF = yMm - yf[idx];
                double L = Math.sqrt(dxF * dxF + dyF * dyF + zfSq[idx]);
                double phi = k * L;
                int v;
                if (binary) {
                    double c = Math.cos(phi);
                    boolean transparent = positive ? (c >= 0.0) : (c < 0.0);
                    v = transparent ? 255 : 0;
                } else {
                    double wrapped = phi - 2.0 * Math.PI * Math.floor(phi / (2.0 * Math.PI));
                    v = (int) Math.min(255, Math.max(0, Math.round(wrapped * (255.0 / (2.0 * Math.PI)))));
                    if (!positive) v = 255 - v;
                }
                row[xPx] = v;
            }
            raster.setSamples(0, yPx, sizePx, 1, 0, row);
        }
        return new RenderResult(img, pixelSizeMm);
    }

    /**
     * Deterministic hash of a pixel location into [0, n). Uses a 32-bit splitmix-style
     * mixer so the output is uniform-looking and stable across runs.
     */
    static int pixelToFocusIndex(int x, int y, int n) {
        int h = (x * 0x9E3779B1) ^ (y * 0x85EBCA77);
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        int idx = h % n;
        if (idx < 0) idx += n;
        return idx;
    }
}

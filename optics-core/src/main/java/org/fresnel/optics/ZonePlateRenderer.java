package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Renders a single Fresnel zone plate (on-axis or off-axis) as a {@link BufferedImage}.
 *
 * <p>The phase at point (x,y) for a focus at (xf, yf, zf) is
 * {@code φ(x,y) = (2π/λ) · √((x-xf)² + (y-yf)² + zf²)}.
 * For on-axis (xf=yf=0) and zf=f this reduces to the classic Fresnel zone plate.
 *
 * <p>For {@link MaskType#BINARY_AMPLITUDE}, pixels are transparent (white) when
 * {@code cos(φ) ≥ 0} (or inverted with {@link Polarity#NEGATIVE}). For
 * {@link MaskType#GREYSCALE_PHASE} the wrapped phase is mapped linearly to 0..255.
 *
 * <p>Pixels outside the circular aperture are rendered black.
 *
 * <p><b>Numerical precision:</b> all path-length and phase calculations are
 * performed in {@code double} (IEEE-754 64-bit, ~15.95 decimal digits). For
 * typical designs the optical path length {@code L} is many millions of
 * wavelengths, so {@code phi = 2π·L/λ} accumulates a large argument before
 * being reduced by {@code cos}.
 *
 * <ul>
 *   <li><b>{@code float} (32-bit)</b> — only ~7 decimal digits of mantissa.
 *       At a worst-case {@code phi ≈ 8e10} rad the absolute error is several
 *       radians, which would scramble the cosine sign. <b>Do not</b> change
 *       inner-loop variables to {@code float}.</li>
 *   <li><b>{@code double} (64-bit)</b> — at the same worst-case {@code phi}
 *       the absolute error is {@code 8e10·2⁻⁵² ≈ 2e-5} rad, i.e. about
 *       0.001 LSB of an 8-bit greyscale phase output. This is three orders
 *       of magnitude tighter than the output quantization. <b>Sufficient.</b></li>
 *   <li><b>{@code java.math.BigDecimal} / arbitrary precision</b> — would be
 *       100×–1000× slower (software-emulated, no FPU), does not support
 *       {@code sqrt}/{@code cos} natively, and adds zero observable quality
 *       because the output sink is 1-bit or 8-bit. <b>Not needed</b> for any
 *       physically realistic design served by this engine.</li>
 * </ul>
 *
 * <p>The precision budget is locked in by
 * {@code ZonePlateRendererTest#worstCasePhasePrecisionFitsInDouble}.
 */
public final class ZonePlateRenderer {

    private ZonePlateRenderer() {}

    public static RenderResult render(SingleZonePlateParameters p) {
        double pixelSizeMm = Units.pixelSizeMm(p.dpi());
        int sizePx = Math.max(2, (int) Math.ceil(p.apertureDiameterMm() / pixelSizeMm));
        // Make size odd so that there is a well-defined center pixel.
        if (sizePx % 2 == 0) sizePx++;

        double radiusMm = p.apertureDiameterMm() / 2.0;
        double radiusPx = radiusMm / pixelSizeMm;
        double radiusPxSq = radiusPx * radiusPx;
        double centerPx = (sizePx - 1) / 2.0;

        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double k = 2.0 * Math.PI / lambdaMm; // angular wavenumber, [1/mm]
        double xfMm = p.targetOffsetXmm();
        double yfMm = p.targetOffsetYmm();
        double zfMm = p.focalLengthMm();
        double zfSqMm = zfMm * zfMm;

        boolean binary = p.maskType() == MaskType.BINARY_AMPLITUDE;
        boolean positive = p.polarity() == Polarity.POSITIVE;

        BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = img.getRaster();
        int[] row = new int[sizePx];

        for (int yPx = 0; yPx < sizePx; yPx++) {
            double dy = (yPx - centerPx);
            double yMm = dy * pixelSizeMm;
            double dyAp = dy;
            for (int xPx = 0; xPx < sizePx; xPx++) {
                double dx = (xPx - centerPx);
                // Aperture clipping (circular)
                if (dx * dx + dyAp * dyAp > radiusPxSq) {
                    row[xPx] = 0;
                    continue;
                }
                double xMm = dx * pixelSizeMm;
                double dxF = xMm - xfMm;
                double dyF = yMm - yfMm;
                double L = Math.sqrt(dxF * dxF + dyF * dyF + zfSqMm);
                double phi = k * L;
                int v;
                if (binary) {
                    double c = Math.cos(phi);
                    boolean transparent = positive ? (c >= 0.0) : (c < 0.0);
                    v = transparent ? 255 : 0;
                } else {
                    // wrap to [0, 2π) then map to 0..255
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
}

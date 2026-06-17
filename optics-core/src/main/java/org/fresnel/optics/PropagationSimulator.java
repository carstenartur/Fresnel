package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Scalar optical propagation simulator for rendered diffractive-element masks.
 *
 * <h2>Approximations and limits</h2>
 * <ul>
 *   <li>Scalar (paraxial) diffraction only — no vector/polarisation effects.</li>
 *   <li>Monochromatic plane-wave illumination at the design wavelength.</li>
 *   <li>{@link PropagationMode#FRESNEL_TF} uses the <em>exact</em> free-space
 *       angular-spectrum transfer function
 *       {@code H(fx,fy)=exp(i·2π/λ·z·√(1–(λfx)²–(λfy)²))};
 *       evanescent components are zeroed.  It is valid for any positive {@code z}
 *       but requires that the sampling grid is fine enough to resolve the
 *       propagating-wave cone (i.e.&nbsp;{@code pixelSizeMm < λ/2}).</li>
 *   <li>{@link PropagationMode#FRAUNHOFER} computes {@code |FFT(field)|²} with
 *       an fftshift so DC is centred.  It gives the correct far-field / focal-plane
 *       intensity pattern but carries no physical distance information.</li>
 *   <li>The mask is zero-padded to the next power-of-two square side before
 *       the FFT.  This avoids wrap-around artefacts and satisfies the power-of-two
 *       requirement of the internal {@link Fft2} implementation.</li>
 *   <li>Intensity output is normalised to 0–255; relative intensities across
 *       different parameter sets are therefore not directly comparable.</li>
 * </ul>
 */
public final class PropagationSimulator {

    private PropagationSimulator() {}

    /**
     * Propagate the field encoded in {@code p.maskImage()} by the distance
     * {@code p.zMm()} and return the normalised intensity image.
     *
     * @param p propagation parameters
     * @return intensity image (greyscale, 0–255) with the same pixel size as the input
     */
    public static RenderResult propagate(PropagationParameters p) {
        int w = p.maskImage().getWidth();
        int h = p.maskImage().getHeight();

        // Pad to the next power-of-two square that fits the mask.
        int n = nextPow2(Math.max(w, h));

        double[] re = new double[n * n];
        double[] im = new double[n * n];

        // Load the mask into the centre of the padded array.
        int xOff = (n - w) / 2;
        int yOff = (n - h) / 2;
        WritableRaster raster = p.maskImage().getRaster();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
                int idx = (y + yOff) * n + (x + xOff);
                double pixel = row[x] / 255.0;
                if (p.maskType() == MaskType.BINARY_AMPLITUDE) {
                    re[idx] = pixel; // 0 (opaque) or 1 (transparent)
                    im[idx] = 0.0;
                } else { // GREYSCALE_PHASE
                    // pixel == 0 signals the aperture boundary (ZonePlateRenderer
                    // renders all out-of-aperture pixels as 0 for every mask type).
                    if (row[x] == 0) {
                        re[idx] = 0.0;
                        im[idx] = 0.0;
                    } else {
                        double phase = pixel * 2.0 * Math.PI;
                        re[idx] = Math.cos(phase);
                        im[idx] = Math.sin(phase);
                    }
                }
            }
        }

        if (p.mode() == PropagationMode.FRAUNHOFER) {
            Fft2.fft2(re, im, n);
            return intensityFftShifted(re, im, n, p.pixelSizeMm());
        }

        // --- FRESNEL_TF: angular-spectrum transfer function ---
        Fft2.fft2(re, im, n);
        applyTransferFunction(re, im, n, p.pixelSizeMm(), Units.nmToMm(p.wavelengthNm()), p.zMm());
        Fft2.ifft2(re, im, n);
        return intensityDirect(re, im, n, p.pixelSizeMm());
    }

    // ---- private helpers ----

    /**
     * Apply the free-space angular-spectrum transfer function in-place.
     *
     * <p>{@code H(fx,fy) = exp(i·k·z·√(1–(λ·fx)²–(λ·fy)²))}
     * Evanescent components ({@code (λfx)²+(λfy)² > 1}) are zeroed.
     */
    private static void applyTransferFunction(double[] re, double[] im, int n,
                                              double pixelSizeMm, double lambdaMm, double zMm) {
        double k = 2.0 * Math.PI / lambdaMm;
        double dfreq = 1.0 / (n * pixelSizeMm); // frequency step in 1/mm

        for (int iy = 0; iy < n; iy++) {
            double fy = freqComponent(iy, n, dfreq);
            double fyLam = fy * lambdaMm;
            for (int ix = 0; ix < n; ix++) {
                double fx = freqComponent(ix, n, dfreq);
                double fxLam = fx * lambdaMm;
                double arg2 = fxLam * fxLam + fyLam * fyLam;
                int idx = iy * n + ix;
                if (arg2 > 1.0) {
                    // Evanescent — zero out.
                    re[idx] = 0.0;
                    im[idx] = 0.0;
                } else {
                    double phase = k * zMm * Math.sqrt(1.0 - arg2);
                    double cosP = Math.cos(phase);
                    double sinP = Math.sin(phase);
                    double aRe = re[idx];
                    double aIm = im[idx];
                    re[idx] = aRe * cosP - aIm * sinP;
                    im[idx] = aRe * sinP + aIm * cosP;
                }
            }
        }
    }

    /**
     * Map FFT array index {@code k} to the corresponding signed spatial frequency
     * (in 1/mm).
     *
     * <p>The FFT layout places positive frequencies at indices 0…N/2–1 and negative
     * frequencies at N/2…N–1, so:
     * <ul>
     *   <li>k in [0, N/2): freq = k·Δf</li>
     *   <li>k in [N/2, N): freq = (k–N)·Δf</li>
     * </ul>
     */
    private static double freqComponent(int k, int n, double dfreq) {
        return (k < n / 2 ? k : k - n) * dfreq;
    }

    /** Build intensity image with fftshift (DC in the centre). */
    private static RenderResult intensityFftShifted(double[] re, double[] im, int n, double pixelSizeMm) {
        // Compute |FFT|² in-place into re[] to avoid a third large array allocation.
        for (int i = 0; i < n * n; i++) {
            double a = re[i], b = im[i];
            re[i] = a * a + b * b;
        }
        double maxI = max(re, n * n);
        double inv = maxI > 1e-30 ? 255.0 / maxI : 0.0;
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster ow = out.getRaster();
        int[] row = new int[n];
        int half = n / 2;
        for (int y = 0; y < n; y++) {
            int sy = (y + half) % n;
            for (int x = 0; x < n; x++) {
                int sx = (x + half) % n;
                row[x] = (int) Math.min(255, Math.round(re[sy * n + sx] * inv));
            }
            ow.setSamples(0, y, n, 1, 0, row);
        }
        return new RenderResult(out, pixelSizeMm);
    }

    /** Build intensity image without shift (spatial output of IFFT). */
    private static RenderResult intensityDirect(double[] re, double[] im, int n, double pixelSizeMm) {
        // Compute intensity in-place into re[] to avoid a third large array allocation.
        for (int i = 0; i < n * n; i++) {
            double a = re[i], b = im[i];
            re[i] = a * a + b * b;
        }
        double maxI = max(re, n * n);
        double inv = maxI > 1e-30 ? 255.0 / maxI : 0.0;
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster ow = out.getRaster();
        int[] row = new int[n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                row[x] = (int) Math.min(255, Math.round(re[y * n + x] * inv));
            }
            ow.setSamples(0, y, n, 1, 0, row);
        }
        return new RenderResult(out, pixelSizeMm);
    }

    private static double max(double[] arr, int len) {
        double m = 0.0;
        for (int i = 0; i < len; i++) if (arr[i] > m) m = arr[i];
        return m;
    }

    /** Returns the smallest power of two that is ≥ {@code v} and ≥ 2. */
    static int nextPow2(int v) {
        if (v < 2) return 2;
        int p = Integer.highestOneBit(v);
        return (p == v) ? p : p << 1;
    }
}

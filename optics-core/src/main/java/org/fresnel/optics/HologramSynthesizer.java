package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Random;

/**
 * Computer-generated hologram synthesis via the Gerchberg–Saxton algorithm.
 *
 * <p>Given a target far-field amplitude pattern (the desired reconstructed image), GS
 * iteratively finds a phase-only near-field (the hologram) whose Fourier transform
 * approximates the target amplitude. The standard loop:
 * <ol>
 *   <li>Start from {@code H = exp(i·random_phase)} in the near-field.</li>
 *   <li>FFT → far-field {@code F}.</li>
 *   <li>Replace amplitude of {@code F} with the target amplitude, keep phase.</li>
 *   <li>IFFT → updated near-field {@code H'}.</li>
 *   <li>Replace amplitude of {@code H'} with 1 (unit aperture); keep phase.</li>
 *   <li>Repeat from step 2.</li>
 * </ol>
 *
 * <p>The synthesised phase is then quantised to either 0/π (binary phase, exported
 * as a 1-bit amplitude-style mask) or continuous greyscale.
 */
public final class HologramSynthesizer {

    private HologramSynthesizer() {}

    public static RenderResult synthesize(HologramParameters p) {
        return synthesize(p, 0xFE5E_1234L);
    }

    /** With explicit RNG seed (deterministic for tests). */
    public static RenderResult synthesize(HologramParameters p, long seed) {
        BufferedImage target = p.targetImage();
        int n = target.getWidth();
        int total = n * n;

        // Target amplitude: sqrt of pixel intensity (so |F|² ≈ target intensity).
        double[] tgtAmp = new double[total];
        WritableRaster tr = target.getRaster();
        int[] buf = new int[n];
        for (int y = 0; y < n; y++) {
            tr.getSamples(0, y, n, 1, 0, buf);
            for (int x = 0; x < n; x++) tgtAmp[y * n + x] = Math.sqrt(buf[x] / 255.0);
        }

        // Initial near-field: unit amplitude, random phase.
        Random rng = new Random(seed);
        double[] re = new double[total];
        double[] im = new double[total];
        for (int i = 0; i < total; i++) {
            double ph = 2.0 * Math.PI * rng.nextDouble();
            re[i] = Math.cos(ph);
            im[i] = Math.sin(ph);
        }

        // The conventional GS loop expects the target amplitude in fft-shifted layout
        // (DC at corner). We supply target with origin already at corner and let GS run.
        for (int it = 0; it < p.iterations(); it++) {
            // Near → far
            Fft2.fft2(re, im, n);
            // Replace amplitude with target amplitude.
            for (int i = 0; i < total; i++) {
                double ar = re[i];
                double ai = im[i];
                double mag = Math.sqrt(ar * ar + ai * ai);
                double scale = mag > 1e-30 ? tgtAmp[i] / mag : tgtAmp[i];
                re[i] = ar * scale;
                im[i] = ai * scale;
            }
            // Far → near
            Fft2.ifft2(re, im, n);
            // Constrain near-field to unit amplitude (phase only).
            for (int i = 0; i < total; i++) {
                double ar = re[i];
                double ai = im[i];
                double mag = Math.sqrt(ar * ar + ai * ai);
                if (mag > 1e-30) {
                    re[i] = ar / mag;
                    im[i] = ai / mag;
                } else {
                    re[i] = 1.0;
                    im[i] = 0.0;
                }
            }
        }

        // Convert near-field phase to a printable mask.
        BufferedImage mask = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster mr = mask.getRaster();
        int[] row = new int[n];
        boolean binary = p.outputType() == HologramParameters.OutputType.BINARY_PHASE;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double phase = Math.atan2(im[y * n + x], re[y * n + x]);
                if (binary) {
                    // Cosine sign as 1-bit.
                    row[x] = Math.cos(phase) >= 0.0 ? 255 : 0;
                } else {
                    // Map [-π, π] → [0, 255]
                    double wrapped = phase + Math.PI;
                    row[x] = (int) Math.min(255, Math.max(0, Math.round(wrapped * (255.0 / (2.0 * Math.PI)))));
                }
            }
            mr.setSamples(0, y, n, 1, 0, row);
        }
        double pixelMm = Units.pixelSizeMm(p.dpi());
        return new RenderResult(mask, pixelMm);
    }

    /**
     * Simulate the optical reconstruction of a phase-only mask: {@code |FFT(H)|²},
     * normalised to 0..255 greyscale. Useful for previewing what the hologram will
     * project before printing.
     */
    public static BufferedImage reconstruct(BufferedImage phaseMask, HologramParameters.OutputType type) {
        int n = phaseMask.getWidth();
        if (phaseMask.getHeight() != n || (n & (n - 1)) != 0)
            throw new IllegalArgumentException("phaseMask must be square power-of-two");
        double[] re = new double[n * n];
        double[] im = new double[n * n];
        WritableRaster r = phaseMask.getRaster();
        int[] row = new int[n];
        for (int y = 0; y < n; y++) {
            r.getSamples(0, y, n, 1, 0, row);
            for (int x = 0; x < n; x++) {
                double phase;
                if (type == HologramParameters.OutputType.BINARY_PHASE) {
                    phase = row[x] >= 128 ? 0.0 : Math.PI;
                } else {
                    phase = (row[x] / 255.0) * 2.0 * Math.PI - Math.PI;
                }
                re[y * n + x] = Math.cos(phase);
                im[y * n + x] = Math.sin(phase);
            }
        }
        Fft2.fft2(re, im, n);
        // Compute intensity, find max for normalisation.
        double[] intensity = new double[n * n];
        double maxI = 0.0;
        for (int i = 0; i < intensity.length; i++) {
            double a = re[i];
            double b = im[i];
            double v = a * a + b * b;
            intensity[i] = v;
            if (v > maxI) maxI = v;
        }
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster ow = out.getRaster();
        double inv = maxI > 1e-30 ? 255.0 / maxI : 0.0;
        // fftshift so DC is centred (more intuitive preview).
        int half = n / 2;
        for (int y = 0; y < n; y++) {
            int sy = (y + half) % n;
            for (int x = 0; x < n; x++) {
                int sx = (x + half) % n;
                int v = (int) Math.min(255, Math.round(intensity[sy * n + sx] * inv));
                row[x] = v;
            }
            ow.setSamples(0, y, n, 1, 0, row);
        }
        return out;
    }
}

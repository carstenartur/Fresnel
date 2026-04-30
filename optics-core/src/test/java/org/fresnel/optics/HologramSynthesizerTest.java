package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class HologramSynthesizerTest {

    @Test
    void fft1RoundTripIdentity() {
        int n = 16;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.sin(i * 0.7) + 0.3 * Math.cos(i * 1.3);
        double[] reCopy = re.clone();
        double[] imCopy = im.clone();
        Fft2.fft1(re, im, n, false);
        Fft2.fft1(re, im, n, true);
        // After inverse, multiply by 1/n manually because fft1 doesn't normalise.
        for (int i = 0; i < n; i++) {
            assertEquals(reCopy[i], re[i] / n, 1e-9, "re[" + i + "]");
            assertEquals(imCopy[i], im[i] / n, 1e-9, "im[" + i + "]");
        }
    }

    @Test
    void fft2RoundTripIdentity() {
        int n = 8;
        double[] re = new double[n * n];
        double[] im = new double[n * n];
        for (int i = 0; i < re.length; i++) re[i] = (i * 0.13) % 1.0;
        double[] reCopy = re.clone();
        Fft2.fft2(re, im, n);
        Fft2.ifft2(re, im, n);
        for (int i = 0; i < re.length; i++) {
            assertEquals(reCopy[i], re[i], 1e-9);
            assertEquals(0.0, im[i], 1e-9);
        }
    }

    @Test
    void synthesisesHologramAndReconstructionApproximatesTarget() {
        BufferedImage target = HologramParameters.syntheticCheckerTarget(32, 4);
        HologramParameters p = new HologramParameters(
                target, 30, HologramParameters.OutputType.GREYSCALE_PHASE, 1200.0);
        RenderResult r = HologramSynthesizer.synthesize(p);
        BufferedImage mask = r.image();
        assertEquals(32, mask.getWidth());
        // Reconstruction should bear *some* resemblance (correlation > 0).
        BufferedImage recon = HologramSynthesizer.reconstruct(mask,
                HologramParameters.OutputType.GREYSCALE_PHASE);
        double corr = correlation(target, recon);
        // GS rarely converges perfectly, but 30 iterations on a 32×32 checker target
        // should produce a positive correlation.
        assertTrue(corr > 0.05, "reconstruction correlation too low: " + corr);
    }

    @Test
    void binaryPhaseProducesBinaryMask() {
        BufferedImage target = HologramParameters.syntheticCheckerTarget(16, 2);
        HologramParameters p = new HologramParameters(
                target, 5, HologramParameters.OutputType.BINARY_PHASE, 600.0);
        BufferedImage mask = HologramSynthesizer.synthesize(p).image();
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                int v = mask.getRaster().getSample(x, y, 0);
                assertTrue(v == 0 || v == 255, "non-binary pixel: " + v);
            }
        }
    }

    @Test
    void rejectsBadParameters() {
        BufferedImage tgt = HologramParameters.syntheticCheckerTarget(32, 4);
        // Non-square target
        BufferedImage bad = new BufferedImage(32, 16, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class,
                () -> new HologramParameters(bad, 10, HologramParameters.OutputType.GREYSCALE_PHASE, 600.0));
        // Non power-of-two
        BufferedImage bad2 = new BufferedImage(33, 33, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class,
                () -> new HologramParameters(bad2, 10, HologramParameters.OutputType.GREYSCALE_PHASE, 600.0));
        // 0 iterations
        assertThrows(IllegalArgumentException.class,
                () -> new HologramParameters(tgt, 0, HologramParameters.OutputType.GREYSCALE_PHASE, 600.0));
    }

    private static double correlation(BufferedImage a, BufferedImage b) {
        int n = a.getWidth();
        double sumA = 0, sumB = 0, sumAB = 0, sumA2 = 0, sumB2 = 0;
        int count = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double va = a.getRaster().getSample(x, y, 0);
                double vb = b.getRaster().getSample(x, y, 0);
                sumA += va; sumB += vb;
                sumAB += va * vb;
                sumA2 += va * va; sumB2 += vb * vb;
                count++;
            }
        }
        double meanA = sumA / count, meanB = sumB / count;
        double cov = sumAB / count - meanA * meanB;
        double varA = sumA2 / count - meanA * meanA;
        double varB = sumB2 / count - meanB * meanB;
        if (varA < 1e-12 || varB < 1e-12) return 0.0;
        return cov / Math.sqrt(varA * varB);
    }
}

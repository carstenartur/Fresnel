package org.fresnel.optics;

/**
 * Minimal in-place 2-D FFT for square power-of-two arrays. Real and imaginary parts
 * stored in separate {@code double[]} arrays of length {@code n*n}, row-major.
 *
 * <p>This implementation prioritises code clarity and zero external dependencies.
 * For large hologram synthesis (≥ 1024 × 1024) it remains acceptable because the
 * Gerchberg–Saxton algorithm typically runs only tens of iterations.
 */
final class Fft2 {

    private Fft2() {}

    /** Forward 2-D FFT in place. {@code n} must be a power of two. */
    static void fft2(double[] re, double[] im, int n) {
        transform2D(re, im, n, false);
    }

    /** Inverse 2-D FFT in place (with 1/N² normalisation). */
    static void ifft2(double[] re, double[] im, int n) {
        transform2D(re, im, n, true);
        double norm = 1.0 / ((double) n * n);
        for (int i = 0; i < re.length; i++) { re[i] *= norm; im[i] *= norm; }
    }

    private static void transform2D(double[] re, double[] im, int n, boolean inverse) {
        if ((n & (n - 1)) != 0) throw new IllegalArgumentException("n must be a power of two: " + n);
        double[] rowR = new double[n];
        double[] rowI = new double[n];
        // FFT each row.
        for (int y = 0; y < n; y++) {
            int off = y * n;
            System.arraycopy(re, off, rowR, 0, n);
            System.arraycopy(im, off, rowI, 0, n);
            fft1(rowR, rowI, n, inverse);
            System.arraycopy(rowR, 0, re, off, n);
            System.arraycopy(rowI, 0, im, off, n);
        }
        // FFT each column.
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) { rowR[y] = re[y * n + x]; rowI[y] = im[y * n + x]; }
            fft1(rowR, rowI, n, inverse);
            for (int y = 0; y < n; y++) { re[y * n + x] = rowR[y]; im[y * n + x] = rowI[y]; }
        }
    }

    /** Iterative Cooley–Tukey 1-D FFT. */
    static void fft1(double[] re, double[] im, int n, boolean inverse) {
        // Bit-reversal permutation.
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j &= ~bit;
            j |= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        // Butterflies.
        double sign = inverse ? +1.0 : -1.0;
        for (int len = 2; len <= n; len <<= 1) {
            double ang = sign * 2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < half; k++) {
                    int aIdx = i + k;
                    int bIdx = i + k + half;
                    double tRe = curRe * re[bIdx] - curIm * im[bIdx];
                    double tIm = curRe * im[bIdx] + curIm * re[bIdx];
                    re[bIdx] = re[aIdx] - tRe;
                    im[bIdx] = im[aIdx] - tIm;
                    re[aIdx] += tRe;
                    im[aIdx] += tIm;
                    double nRe = curRe * wRe - curIm * wIm;
                    double nIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                    curIm = nIm;
                }
            }
        }
    }
}

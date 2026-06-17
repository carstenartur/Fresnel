package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PropagationSimulator}.
 *
 * <p>All tests use small, low-DPI configurations so they run fast in CI (no image
 * larger than 128 × 128 pixels).
 */
class PropagationSimulatorTest {

    // ---- nextPow2 ----

    @Test
    void nextPow2SmallValues() {
        assertEquals(2, PropagationSimulator.nextPow2(1));
        assertEquals(2, PropagationSimulator.nextPow2(2));
        assertEquals(4, PropagationSimulator.nextPow2(3));
        assertEquals(4, PropagationSimulator.nextPow2(4));
        assertEquals(8, PropagationSimulator.nextPow2(5));
        assertEquals(64, PropagationSimulator.nextPow2(63));
        assertEquals(64, PropagationSimulator.nextPow2(64));
        assertEquals(128, PropagationSimulator.nextPow2(65));
    }

    // ---- parameter validation ----

    @Test
    void rejectsNullMask() {
        assertThrows(IllegalArgumentException.class, () ->
                new PropagationParameters(null, MaskType.BINARY_AMPLITUDE,
                        0.1, 3.2, 550.0, 100.0, PropagationMode.FRAUNHOFER));
    }

    @Test
    void rejectsNonPositivePixelSize() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class, () ->
                new PropagationParameters(img, MaskType.BINARY_AMPLITUDE,
                        0.0, 3.2, 550.0, 100.0, PropagationMode.FRAUNHOFER));
    }

    @Test
    void rejectsNonPositiveApertureDiameter() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class, () ->
                new PropagationParameters(img, MaskType.BINARY_AMPLITUDE,
                        0.1, 0.0, 550.0, 100.0, PropagationMode.FRAUNHOFER));
    }

    @Test
    void rejectsNonPositiveZ() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        assertThrows(IllegalArgumentException.class, () ->
                new PropagationParameters(img, MaskType.BINARY_AMPLITUDE,
                        0.1, 3.2, 550.0, 0.0, PropagationMode.FRAUNHOFER));
    }

    // ---- Fraunhofer mode: square aperture ----

    /**
     * A uniform (all-white) square aperture in BINARY_AMPLITUDE mode should
     * produce a non-trivial Fraunhofer pattern — bright centre, falling off toward
     * the edges.
     */
    @Test
    void fraunhoferSquareApertureBrightAtCentre() {
        // Build a small all-white (fully open) aperture image.
        int side = 32;
        BufferedImage aperture = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        fillGrey(aperture, 255);

        double pixMm = 0.1;
        PropagationParameters p = new PropagationParameters(
                aperture, MaskType.BINARY_AMPLITUDE, pixMm, side * pixMm, 550.0, 100.0, PropagationMode.FRAUNHOFER);
        RenderResult r = PropagationSimulator.propagate(p);

        BufferedImage out = r.image();
        // Output must be a valid image.
        assertTrue(out.getWidth() > 0 && out.getHeight() > 0);

        // Centre pixel should be bright (DC component of a uniform aperture is maximum).
        int cx = out.getWidth() / 2;
        int cy = out.getHeight() / 2;
        int centerVal = out.getRaster().getSample(cx, cy, 0);
        // The centre of the Fraunhofer pattern of a uniform aperture is the brightest pixel.
        assertEquals(255, centerVal, "centre pixel should be 255 (normalised max)");
    }

    /**
     * The corner of the Fraunhofer pattern of a uniform aperture should be much
     * darker than the centre.
     */
    @Test
    void fraunhoferApertureCornerDarkerThanCentre() {
        int side = 32;
        BufferedImage aperture = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        fillGrey(aperture, 255);

        PropagationParameters p = new PropagationParameters(
                aperture, MaskType.BINARY_AMPLITUDE, 0.1, side * 0.1, 550.0, 100.0, PropagationMode.FRAUNHOFER);
        RenderResult r = PropagationSimulator.propagate(p);

        BufferedImage out = r.image();
        int cx = out.getWidth() / 2;
        int cy = out.getHeight() / 2;
        int centerVal = out.getRaster().getSample(cx, cy, 0);
        int cornerVal = out.getRaster().getSample(0, 0, 0);
        assertTrue(centerVal > cornerVal + 50, "centre should be much brighter than corner");
    }

    // ---- Fresnel TF mode: uniform square aperture ----

    /**
     * A uniform aperture propagated a short distance in FRESNEL_TF mode should
     * still be mostly bright near the centre of the padded aperture region.
     */
    @Test
    void fresnelTfUniformApertureNonZero() {
        int side = 32;
        BufferedImage aperture = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        fillGrey(aperture, 255);

        PropagationParameters p = new PropagationParameters(
                aperture, MaskType.BINARY_AMPLITUDE, 0.1, side * 0.1, 550.0, 1.0, PropagationMode.FRESNEL_TF);
        RenderResult r = PropagationSimulator.propagate(p);

        BufferedImage out = r.image();
        // Result must contain non-zero pixels (energy is preserved on propagation).
        int maxVal = maxPixel(out);
        assertTrue(maxVal > 0, "propagated field should have non-zero intensity");
    }

    // ---- Synthetic converging-lens focal test ----

    /**
     * Build a synthetic converging thin-lens phase image and propagate it to the
     * focal distance with FRESNEL_TF.  The lens phase is
     * {@code φ(r) = –π·r²/(λ·f)} which is well-sampled at the chosen pixel size.
     *
     * <p>Parameters: pixelSizeMm = 0.010 mm, aperture radius = 15 px (0.15 mm),
     * f = 10 mm, λ = 550 nm.  The phase change per pixel at the aperture edge is
     * ≈ 0.27 cycles/pixel, comfortably below the Nyquist limit of 0.5 cycles/pixel.
     * After padding the 31 × 31 aperture image to the next power-of-two (32 × 32),
     * propagation at z = f = 10 mm must produce a bright central spot.
     */
    @Test
    void fresnelTfConvergingLensProducesCentralFocusAtFocalDistance() {
        double pixMm = 0.010;
        double lambdaMm = 0.00055;
        double fMm = 10.0;
        int apertureRadiusPx = 15;
        int side = 2 * apertureRadiusPx + 1; // 31 × 31, centred pixel at (15,15)

        BufferedImage lensImg = syntheticConvergingLens(side, apertureRadiusPx, pixMm, lambdaMm, fMm);

        PropagationParameters p = new PropagationParameters(
                lensImg, MaskType.GREYSCALE_PHASE, pixMm, 2.0 * apertureRadiusPx * pixMm,
                lambdaMm * 1e6, fMm, PropagationMode.FRESNEL_TF);
        RenderResult result = PropagationSimulator.propagate(p);

        BufferedImage out = result.image();
        int n = out.getWidth(); // nextPow2(31) = 32

        // Lens centre in padded grid: offset = (n-side)/2, centre px = (n-side)/2 + apertureRadiusPx
        int expectedCx = (n - side) / 2 + apertureRadiusPx;
        int expectedCy = expectedCx;

        int peakX = 0, peakY = 0, peakVal = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = out.getRaster().getSample(x, y, 0);
                if (v > peakVal) { peakVal = v; peakX = x; peakY = y; }
            }
        }
        assertTrue(peakVal > 0, "expected non-zero peak");
        int dist = (int) Math.round(Math.hypot(peakX - expectedCx, peakY - expectedCy));
        final int fpx = peakX, fpy = peakY;
        assertTrue(dist <= n / 8,
                () -> "peak at (" + fpx + "," + fpy + ") far from expected focus ("
                        + expectedCx + "," + expectedCy + ")");
    }

    /**
     * A defocused propagation distance should yield a visibly different intensity
     * pattern at the lens axis than the in-focus distance.
     */
    @Test
    void defocusedPropagationDifferentFromFocused() {
        double pixMm = 0.010;
        double lambdaMm = 0.00055;
        double fMm = 10.0;
        int apertureRadiusPx = 15;
        int side = 2 * apertureRadiusPx + 1;

        BufferedImage lensImg = syntheticConvergingLens(side, apertureRadiusPx, pixMm, lambdaMm, fMm);

        PropagationParameters focused = new PropagationParameters(
                lensImg, MaskType.GREYSCALE_PHASE, pixMm, 2.0 * apertureRadiusPx * pixMm,
                lambdaMm * 1e6, fMm, PropagationMode.FRESNEL_TF);
        PropagationParameters defocused = new PropagationParameters(
                lensImg, MaskType.GREYSCALE_PHASE, pixMm, 2.0 * apertureRadiusPx * pixMm,
                lambdaMm * 1e6, fMm * 1.5, PropagationMode.FRESNEL_TF);

        RenderResult rFoc = PropagationSimulator.propagate(focused);
        RenderResult rDef = PropagationSimulator.propagate(defocused);

        // Compare full intensity maps — they must differ somewhere
        BufferedImage imgFoc = rFoc.image();
        BufferedImage imgDef = rDef.image();
        int n = imgFoc.getWidth();
        boolean anyDiff = false;
        outer:
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (imgFoc.getRaster().getSample(x, y, 0) != imgDef.getRaster().getSample(x, y, 0)) {
                    anyDiff = true;
                    break outer;
                }
            }
        }
        assertTrue(anyDiff, "focused and defocused propagation should give different intensity maps");
    }

    // ---- helper: synthetic converging thin-lens phase image ----

    /**
     * Create a greyscale image encoding the converging thin-lens phase
     * {@code φ(r) = –π·r²/(λ·f)} for pixels inside {@code apertureRadiusPx},
     * zero (opaque) outside.
     *
     * @param side              image side (pixels)
     * @param apertureRadiusPx  aperture radius (pixels)
     * @param pixMm             pixel size in mm
     * @param lambdaMm          wavelength in mm
     * @param fMm               focal length in mm
     */
    private static BufferedImage syntheticConvergingLens(
            int side, int apertureRadiusPx, double pixMm, double lambdaMm, double fMm) {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        int cx = side / 2, cy = side / 2;
        int[] row = new int[side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > apertureRadiusPx * apertureRadiusPx) {
                    row[x] = 0;
                } else {
                    double rMm = Math.sqrt(dx * dx + dy * dy) * pixMm;
                    double phi = -Math.PI * rMm * rMm / (lambdaMm * fMm);
                    // wrap to [0, 2π) and map to 0..255
                    double wrapped = ((phi % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                    row[x] = (int) Math.min(255, Math.round(wrapped / (2 * Math.PI) * 255));
                }
            }
            img.getRaster().setSamples(0, y, side, 1, 0, row);
        }
        return img;
    }

    // ---- Greyscale-phase mask ----

    /**
     * A greyscale-phase mask with constant phase (all 128) approximates unit amplitude,
     * so its Fraunhofer pattern should still have a bright centre.
     */
    @Test
    void fraunhoferGreyscalePhaseConstantMask() {
        int side = 32;
        double pixMm = 0.1;
        BufferedImage phaseImg = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        fillGrey(phaseImg, 128); // phase ≈ π (constant)

        PropagationParameters p = new PropagationParameters(
                phaseImg, MaskType.GREYSCALE_PHASE, pixMm, side * pixMm, 550.0, 100.0, PropagationMode.FRAUNHOFER);
        RenderResult r = PropagationSimulator.propagate(p);

        int maxVal = maxPixel(r.image());
        assertTrue(maxVal > 0, "constant phase mask should produce non-zero output");
    }

    /**
     * A GREYSCALE_PHASE image filled entirely with pixel value 0 (phase = 0)
     * must produce non-zero Fraunhofer output.  Phase 0 is a valid in-aperture
     * value (unit amplitude, zero phase shift); it must <em>not</em> be mistaken
     * for an out-of-aperture blocked pixel.
     */
    @Test
    void greyscalePhaseZeroPixelInsideApertureHasUnitAmplitude() {
        int side = 32;
        double pixMm = 0.1;
        BufferedImage allZeroPhase = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        fillGrey(allZeroPhase, 0); // phase = 0 everywhere inside aperture

        PropagationParameters p = new PropagationParameters(
                allZeroPhase, MaskType.GREYSCALE_PHASE, pixMm, side * pixMm,
                550.0, 100.0, PropagationMode.FRAUNHOFER);
        RenderResult r = PropagationSimulator.propagate(p);

        int maxVal = maxPixel(r.image());
        assertTrue(maxVal > 0,
                "pixel=0 (phase=0) inside aperture should give unit amplitude, not zero amplitude");
    }

    // ---- helpers ----

    private static void fillGrey(BufferedImage img, int value) {
        int w = img.getWidth(), h = img.getHeight();
        int[] row = new int[w];
        java.util.Arrays.fill(row, value);
        for (int y = 0; y < h; y++) img.getRaster().setSamples(0, y, w, 1, 0, row);
    }

    private static int maxPixel(BufferedImage img) {
        int max = 0;
        int w = img.getWidth(), h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRaster().getSamples(0, y, w, 1, 0, row);
            for (int v : row) if (v > max) max = v;
        }
        return max;
    }
}

package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundOrientedSchlierenRendererTest {

    @Test
    void sameParametersProduceExactlyTheSamePixels() throws Exception {
        BackgroundOrientedSchlierenParameters parameters = compact(1234L, false, false);

        BackgroundOrientedSchlierenRenderer.Result first =
                BackgroundOrientedSchlierenRenderer.render(parameters);
        BackgroundOrientedSchlierenRenderer.Result second =
                BackgroundOrientedSchlierenRenderer.render(parameters);

        assertEquals(first.dotCount(), second.dotCount());
        assertEquals(pixelSha256(first.image()), pixelSha256(second.image()));
    }

    @Test
    void seedChangesTheTargetButNotItsRequestedDensity() throws Exception {
        BackgroundOrientedSchlierenRenderer.Result first =
                BackgroundOrientedSchlierenRenderer.render(compact(1L, false, false));
        BackgroundOrientedSchlierenRenderer.Result second =
                BackgroundOrientedSchlierenRenderer.render(compact(2L, false, false));

        assertEquals(first.dotCount(), second.dotCount());
        assertNotEquals(pixelSha256(first.image()), pixelSha256(second.image()));
    }

    @Test
    void placementHonoursTheExactMinimumCenterDistance() {
        BackgroundOrientedSchlierenParameters parameters = compact(44L, false, false);
        List<BackgroundOrientedSchlierenRenderer.Dot> dots =
                BackgroundOrientedSchlierenRenderer.placeDots(parameters);
        long minimum = parameters.dotDiameterPx() + parameters.minimumDotSpacingPx();
        long minimumSquared = minimum * minimum;

        for (int left = 0; left < dots.size(); left++) {
            for (int right = left + 1; right < dots.size(); right++) {
                long dx = (long) dots.get(left).centerX() - dots.get(right).centerX();
                long dy = (long) dots.get(left).centerY() - dots.get(right).centerY();
                assertTrue(dx * dx + dy * dy >= minimumSquared);
            }
        }
    }

    @Test
    void borderRemainsBackgroundWhenFiducialsAreDisabled() {
        BackgroundOrientedSchlierenParameters parameters = compact(9L, false, false);
        BufferedImage image =
                BackgroundOrientedSchlierenRenderer.render(parameters).image();

        int white = image.getRGB(0, 0);
        for (int x = 0; x < image.getWidth(); x++) {
            assertEquals(white, image.getRGB(x, 0));
            assertEquals(white, image.getRGB(x, parameters.borderPx() - 1));
        }
    }

    @Test
    void inversionSwapsBackgroundPolarity() {
        BufferedImage normal =
                BackgroundOrientedSchlierenRenderer.render(compact(3L, false, false)).image();
        BufferedImage inverted =
                BackgroundOrientedSchlierenRenderer.render(compact(3L, false, true)).image();

        assertNotEquals(normal.getRGB(0, 0), inverted.getRGB(0, 0));
    }

    @Test
    void infeasibleDensityIsRejectedInsteadOfRelaxingSpacing() {
        BackgroundOrientedSchlierenParameters parameters =
                new BackgroundOrientedSchlierenParameters(
                        256, 256, 300, 5, 5, 0.35, 40, 20, false, false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundOrientedSchlierenRenderer.render(parameters));
        assertTrue(exception.getMessage().contains("infeasible"));
    }

    @Test
    void nullParametersAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundOrientedSchlierenRenderer.render(null));
    }

    private static BackgroundOrientedSchlierenParameters compact(
            long seed,
            boolean fiducials,
            boolean inverted) {
        return new BackgroundOrientedSchlierenParameters(
                320,
                256,
                300,
                seed,
                7,
                0.05,
                3,
                32,
                fiducials,
                inverted);
    }

    private static String pixelSha256(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                digest.update((byte) (rgb >>> 24));
                digest.update((byte) (rgb >>> 16));
                digest.update((byte) (rgb >>> 8));
                digest.update((byte) rgb);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

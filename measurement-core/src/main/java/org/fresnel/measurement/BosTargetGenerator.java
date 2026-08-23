package org.fresnel.measurement;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Deterministic generator for random-dot Background-Oriented Schlieren targets. */
public final class BosTargetGenerator {

    private BosTargetGenerator() {}

    public static BosTarget generate(BosTargetParameters parameters) {
        if (parameters == null) throw new IllegalArgumentException("parameters must not be null");

        int width = parameters.widthPx();
        int height = parameters.heightPx();
        int background = parameters.invertPattern() ? 0 : 255;
        int foreground = 255 - background;
        byte[] pixels = new byte[Math.multiplyExact(width, height)];
        Arrays.fill(pixels, (byte) background);

        BosTarget.Region activeRegion = new BosTarget.Region(
                parameters.borderPx(),
                parameters.borderPx(),
                parameters.activeWidthPx(),
                parameters.activeHeightPx());

        List<BosTarget.Fiducial> fiducials = parameters.fiducialsEnabled()
                ? drawFiducials(pixels, width, height, parameters, foreground, background)
                : List.of();

        int pitch = BosTargetParameters.cellPitchPx(
                parameters.dotDiameterPx(), parameters.minimumDotSpacingPx());
        int jitter = BosTargetParameters.jitterRadiusPx(parameters.dotDiameterPx());
        int columns = activeRegion.width() / pitch;
        int rows = activeRegion.height() / pitch;
        int candidateCount = Math.multiplyExact(columns, rows);
        double nominalDotArea = Math.PI * square(parameters.dotDiameterPx() / 2.0);
        int requestedDotCount = (int) Math.round(
                parameters.targetFillRatio()
                        * activeRegion.width()
                        * activeRegion.height()
                        / nominalDotArea);

        int[] candidates = new int[candidateCount];
        for (int i = 0; i < candidateCount; i++) candidates[i] = i;

        DeterministicRandom random = new DeterministicRandom(parameters.patternSeed());
        int occupiedWidth = columns * pitch;
        int occupiedHeight = rows * pitch;
        int originX = activeRegion.x() + (activeRegion.width() - occupiedWidth) / 2;
        int originY = activeRegion.y() + (activeRegion.height() - occupiedHeight) / 2;

        for (int dotIndex = 0; dotIndex < requestedDotCount; dotIndex++) {
            int selectedIndex = dotIndex + random.nextInt(candidateCount - dotIndex);
            int candidate = candidates[selectedIndex];
            candidates[selectedIndex] = candidates[dotIndex];
            candidates[dotIndex] = candidate;

            int cellX = candidate % columns;
            int cellY = candidate / columns;
            int centerX = originX + cellX * pitch + pitch / 2
                    + random.nextInt(2 * jitter + 1) - jitter;
            int centerY = originY + cellY * pitch + pitch / 2
                    + random.nextInt(2 * jitter + 1) - jitter;
            drawDisc(
                    pixels,
                    width,
                    height,
                    centerX,
                    centerY,
                    parameters.dotDiameterPx(),
                    foreground);
        }

        int foregroundPixels = countValue(pixels, width, activeRegion, foreground);
        double actualFillRatio = (double) foregroundPixels
                / ((long) activeRegion.width() * activeRegion.height());
        String semanticSha256 = semanticSha256(parameters, activeRegion, pixels);
        return new BosTarget(
                parameters,
                activeRegion,
                requestedDotCount,
                actualFillRatio,
                pitch,
                fiducials,
                pixels,
                semanticSha256);
    }

    private static List<BosTarget.Fiducial> drawFiducials(
            byte[] pixels,
            int width,
            int height,
            BosTargetParameters parameters,
            int foreground,
            int background) {
        int border = parameters.borderPx();
        int margin = Math.max(4, border / 8);
        int desired = Math.max(12, parameters.dotDiameterPx() * 2 + 2);
        int size = Math.min(border - 2 * margin, desired);
        if (size < 8) {
            throw new IllegalArgumentException("border is too small for orientation fiducials");
        }

        List<BosTarget.Fiducial> result = new ArrayList<>(4);
        BosTarget.Region topLeft = new BosTarget.Region(margin, margin, size, size);
        BosTarget.Region topRight = new BosTarget.Region(
                width - border + margin, margin, size, size);
        BosTarget.Region bottomLeft = new BosTarget.Region(
                margin, height - border + margin, size, size);
        BosTarget.Region bottomRight = new BosTarget.Region(
                width - border + margin, height - border + margin, size, size);

        fillRectangle(pixels, width, height, topLeft, foreground);
        result.add(new BosTarget.Fiducial(
                "top-left", BosTarget.FiducialShape.SOLID_SQUARE, topLeft));

        drawRing(pixels, width, height, topRight, foreground, background);
        result.add(new BosTarget.Fiducial(
                "top-right", BosTarget.FiducialShape.RING, topRight));

        drawLShape(pixels, width, height, bottomLeft, foreground);
        result.add(new BosTarget.Fiducial(
                "bottom-left", BosTarget.FiducialShape.L_SHAPE, bottomLeft));

        drawCross(pixels, width, height, bottomRight, foreground);
        result.add(new BosTarget.Fiducial(
                "bottom-right", BosTarget.FiducialShape.CROSS, bottomRight));
        return List.copyOf(result);
    }

    private static void drawDisc(
            byte[] pixels,
            int width,
            int height,
            int centerX,
            int centerY,
            int diameter,
            int value) {
        double radius = diameter / 2.0;
        double radiusSquared = radius * radius;
        int minimumX = Math.max(0, centerX - diameter / 2);
        int minimumY = Math.max(0, centerY - diameter / 2);
        int maximumX = Math.min(width - 1, minimumX + diameter - 1);
        int maximumY = Math.min(height - 1, minimumY + diameter - 1);
        double pixelCenterOffset = (diameter % 2 == 0) ? 0.5 : 0.0;
        for (int y = minimumY; y <= maximumY; y++) {
            double dy = y - centerY + pixelCenterOffset;
            for (int x = minimumX; x <= maximumX; x++) {
                double dx = x - centerX + pixelCenterOffset;
                if (dx * dx + dy * dy <= radiusSquared) {
                    pixels[y * width + x] = (byte) value;
                }
            }
        }
    }

    private static void fillRectangle(
            byte[] pixels,
            int width,
            int height,
            BosTarget.Region region,
            int value) {
        requireInside(region, width, height);
        for (int y = region.y(); y < region.maxYExclusive(); y++) {
            Arrays.fill(
                    pixels,
                    y * width + region.x(),
                    y * width + region.maxXExclusive(),
                    (byte) value);
        }
    }

    private static void drawRing(
            byte[] pixels,
            int width,
            int height,
            BosTarget.Region region,
            int foreground,
            int background) {
        int diameter = Math.min(region.width(), region.height());
        int centerX = region.x() + region.width() / 2;
        int centerY = region.y() + region.height() / 2;
        drawDisc(pixels, width, height, centerX, centerY, diameter, foreground);
        drawDisc(pixels, width, height, centerX, centerY, Math.max(2, diameter / 2), background);
    }

    private static void drawLShape(
            byte[] pixels,
            int width,
            int height,
            BosTarget.Region region,
            int foreground) {
        int thickness = Math.max(2, region.width() / 4);
        fillRectangle(pixels, width, height,
                new BosTarget.Region(region.x(), region.y(), thickness, region.height()),
                foreground);
        fillRectangle(pixels, width, height,
                new BosTarget.Region(region.x(), region.maxYExclusive() - thickness,
                        region.width(), thickness),
                foreground);
    }

    private static void drawCross(
            byte[] pixels,
            int width,
            int height,
            BosTarget.Region region,
            int foreground) {
        int thickness = Math.max(2, region.width() / 5);
        int centerX = region.x() + (region.width() - thickness) / 2;
        int centerY = region.y() + (region.height() - thickness) / 2;
        fillRectangle(pixels, width, height,
                new BosTarget.Region(centerX, region.y(), thickness, region.height()),
                foreground);
        fillRectangle(pixels, width, height,
                new BosTarget.Region(region.x(), centerY, region.width(), thickness),
                foreground);
    }

    private static void requireInside(BosTarget.Region region, int width, int height) {
        if (region.maxXExclusive() > width || region.maxYExclusive() > height) {
            throw new IllegalArgumentException("drawing region exceeds target bounds");
        }
    }

    private static int countValue(
            byte[] pixels,
            int width,
            BosTarget.Region region,
            int expected) {
        int count = 0;
        for (int y = region.y(); y < region.maxYExclusive(); y++) {
            int offset = y * width + region.x();
            for (int x = 0; x < region.width(); x++) {
                if (Byte.toUnsignedInt(pixels[offset + x]) == expected) count++;
            }
        }
        return count;
    }

    private static String semanticSha256(
            BosTargetParameters parameters,
            BosTarget.Region activeRegion,
            byte[] pixels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(BosTargetParameters.ALGORITHM_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            ByteBuffer metadata = ByteBuffer.allocate(80);
            metadata.putInt(parameters.widthPx());
            metadata.putInt(parameters.heightPx());
            metadata.putLong(Double.doubleToLongBits(parameters.intendedDpi()));
            metadata.putLong(parameters.patternSeed());
            metadata.putInt(parameters.dotDiameterPx());
            metadata.putLong(Double.doubleToLongBits(parameters.targetFillRatio()));
            metadata.putInt(parameters.minimumDotSpacingPx());
            metadata.putInt(parameters.borderPx());
            metadata.put((byte) (parameters.fiducialsEnabled() ? 1 : 0));
            metadata.put((byte) (parameters.invertPattern() ? 1 : 0));
            metadata.putInt(activeRegion.x());
            metadata.putInt(activeRegion.y());
            metadata.putInt(activeRegion.width());
            metadata.putInt(activeRegion.height());
            digest.update(metadata.array(), 0, metadata.position());
            digest.update(pixels);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static double square(double value) {
        return value * value;
    }

    /** Small explicitly versioned PRNG; independent of JDK random implementations. */
    private static final class DeterministicRandom {
        private long state;

        private DeterministicRandom(long seed) {
            this.state = seed;
        }

        private long nextLong() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        private int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }
    }
}

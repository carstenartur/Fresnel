package org.fresnel.optics;

/**
 * Immutable parameters for a deterministic background-oriented schlieren target.
 *
 * <p>Pixel dimensions are authoritative. {@code intendedDpi} records the intended
 * physical use but never changes the generated raster. The complete parameter
 * record, including {@code patternSeed}, therefore defines one reproducible target.</p>
 */
public record BackgroundOrientedSchlierenParameters(
        int widthPx,
        int heightPx,
        double intendedDpi,
        long patternSeed,
        int dotDiameterPx,
        double targetFillRatio,
        int minimumDotSpacingPx,
        int borderPx,
        boolean fiducialsEnabled,
        boolean invertPattern
) {
    public static final int MIN_SIDE_PX = 256;
    public static final int MAX_SIDE_PX = 4096;
    public static final long MAX_PIXELS = 12_000_000L;
    public static final int MAX_DOTS = 500_000;

    public BackgroundOrientedSchlierenParameters {
        if (widthPx < MIN_SIDE_PX || widthPx > MAX_SIDE_PX) {
            throw new IllegalArgumentException(
                    "widthPx must be between " + MIN_SIDE_PX + " and " + MAX_SIDE_PX);
        }
        if (heightPx < MIN_SIDE_PX || heightPx > MAX_SIDE_PX) {
            throw new IllegalArgumentException(
                    "heightPx must be between " + MIN_SIDE_PX + " and " + MAX_SIDE_PX);
        }
        if ((long) widthPx * heightPx > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "BOS target exceeds the " + MAX_PIXELS + " pixel safety limit");
        }
        if (!Double.isFinite(intendedDpi) || intendedDpi < 50.0 || intendedDpi > 2400.0) {
            throw new IllegalArgumentException("intendedDpi must be between 50 and 2400");
        }
        if (dotDiameterPx < 3 || dotDiameterPx > 64) {
            throw new IllegalArgumentException("dotDiameterPx must be between 3 and 64");
        }
        if (!Double.isFinite(targetFillRatio)
                || targetFillRatio < 0.01
                || targetFillRatio > 0.35) {
            throw new IllegalArgumentException(
                    "targetFillRatio must be between 0.01 and 0.35");
        }
        if (minimumDotSpacingPx < 0 || minimumDotSpacingPx > 64) {
            throw new IllegalArgumentException(
                    "minimumDotSpacingPx must be between 0 and 64");
        }
        if (borderPx < 0 || borderPx > Math.min(widthPx, heightPx) / 3) {
            throw new IllegalArgumentException(
                    "borderPx must be non-negative and leave a usable target area");
        }
        int usableWidthPx = widthPx - 2 * borderPx;
        int usableHeightPx = heightPx - 2 * borderPx;
        if (usableWidthPx < dotDiameterPx || usableHeightPx < dotDiameterPx) {
            throw new IllegalArgumentException(
                    "borderPx and dotDiameterPx leave no usable target area");
        }
        int minimumFiducialBorderPx = Math.max(16, dotDiameterPx * 2);
        if (fiducialsEnabled && borderPx < minimumFiducialBorderPx) {
            throw new IllegalArgumentException(
                    "fiducials require borderPx >= " + minimumFiducialBorderPx);
        }
        int requestedDotCount = requestedDotCount(
                usableWidthPx, usableHeightPx, dotDiameterPx, targetFillRatio);
        if (requestedDotCount < 1) {
            throw new IllegalArgumentException("targetFillRatio requests no dots");
        }
        if (requestedDotCount > MAX_DOTS) {
            throw new IllegalArgumentException(
                    "targetFillRatio requests more than " + MAX_DOTS + " dots");
        }
    }

    public int usableWidthPx() {
        return widthPx - 2 * borderPx;
    }

    public int usableHeightPx() {
        return heightPx - 2 * borderPx;
    }

    public int minimumFiducialBorderPx() {
        return Math.max(16, dotDiameterPx * 2);
    }

    public int requestedDotCount() {
        return requestedDotCount(
                usableWidthPx(), usableHeightPx(), dotDiameterPx, targetFillRatio);
    }

    private static int requestedDotCount(
            int usableWidthPx,
            int usableHeightPx,
            int dotDiameterPx,
            double targetFillRatio) {
        double radius = dotDiameterPx / 2.0;
        double dotArea = Math.PI * radius * radius;
        double usableArea = (double) usableWidthPx * usableHeightPx;
        return Math.max(1, (int) Math.round(usableArea * targetFillRatio / dotArea));
    }

    public static BackgroundOrientedSchlierenParameters defaults() {
        return new BackgroundOrientedSchlierenParameters(
                1920,
                1080,
                300.0,
                20_260_824L,
                7,
                0.12,
                3,
                64,
                true,
                false);
    }
}

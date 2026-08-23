package org.fresnel.measurement;

/**
 * Immutable parameters for a deterministic Background-Oriented Schlieren target.
 *
 * <p>The first version deliberately uses pixel-domain geometry. A displayed target
 * can therefore be shown at one device pixel per source pixel, while the intended
 * DPI gives downloaded PNGs a reproducible physical print scale.</p>
 */
public record BosTargetParameters(
        int widthPx,
        int heightPx,
        double intendedDpi,
        long patternSeed,
        int dotDiameterPx,
        double targetFillRatio,
        int minimumDotSpacingPx,
        int borderPx,
        boolean fiducialsEnabled,
        boolean invertPattern) {

    public static final String ALGORITHM_VERSION = "background-oriented-schlieren-target/1";
    public static final int MIN_WIDTH_PX = 320;
    public static final int MAX_WIDTH_PX = 6000;
    public static final int MIN_HEIGHT_PX = 240;
    public static final int MAX_HEIGHT_PX = 6000;
    public static final long MAX_PIXELS = 24_000_000L;
    public static final int MIN_DOT_DIAMETER_PX = 2;
    public static final int MAX_DOT_DIAMETER_PX = 32;
    public static final int MIN_SPACING_PX = 1;
    public static final int MAX_SPACING_PX = 32;
    public static final int MIN_BORDER_PX = 24;
    public static final int MAX_BORDER_PX = 512;
    public static final double MIN_FILL_RATIO = 0.02;
    public static final double MAX_FILL_RATIO = 0.25;
    public static final double MIN_DPI = 50.0;
    public static final double MAX_DPI = 2400.0;

    public BosTargetParameters {
        if (widthPx < MIN_WIDTH_PX || widthPx > MAX_WIDTH_PX) {
            throw new IllegalArgumentException(
                    "widthPx must be between " + MIN_WIDTH_PX + " and " + MAX_WIDTH_PX);
        }
        if (heightPx < MIN_HEIGHT_PX || heightPx > MAX_HEIGHT_PX) {
            throw new IllegalArgumentException(
                    "heightPx must be between " + MIN_HEIGHT_PX + " and " + MAX_HEIGHT_PX);
        }
        long pixels = Math.multiplyExact((long) widthPx, heightPx);
        if (pixels > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "target exceeds the maximum of " + MAX_PIXELS + " pixels");
        }
        if (!Double.isFinite(intendedDpi)
                || intendedDpi < MIN_DPI
                || intendedDpi > MAX_DPI) {
            throw new IllegalArgumentException(
                    "intendedDpi must be finite and between " + MIN_DPI + " and " + MAX_DPI);
        }
        if (dotDiameterPx < MIN_DOT_DIAMETER_PX
                || dotDiameterPx > MAX_DOT_DIAMETER_PX) {
            throw new IllegalArgumentException(
                    "dotDiameterPx must be between " + MIN_DOT_DIAMETER_PX
                            + " and " + MAX_DOT_DIAMETER_PX);
        }
        if (!Double.isFinite(targetFillRatio)
                || targetFillRatio < MIN_FILL_RATIO
                || targetFillRatio > MAX_FILL_RATIO) {
            throw new IllegalArgumentException(
                    "targetFillRatio must be finite and between " + MIN_FILL_RATIO
                            + " and " + MAX_FILL_RATIO);
        }
        if (minimumDotSpacingPx < MIN_SPACING_PX
                || minimumDotSpacingPx > MAX_SPACING_PX) {
            throw new IllegalArgumentException(
                    "minimumDotSpacingPx must be between " + MIN_SPACING_PX
                            + " and " + MAX_SPACING_PX);
        }
        if (borderPx < MIN_BORDER_PX || borderPx > MAX_BORDER_PX) {
            throw new IllegalArgumentException(
                    "borderPx must be between " + MIN_BORDER_PX + " and " + MAX_BORDER_PX);
        }
        if (borderPx * 4 >= Math.min(widthPx, heightPx)) {
            throw new IllegalArgumentException(
                    "borderPx must leave an active region larger than half the shorter target side");
        }

        int minimumMarkerBorder = dotDiameterPx * 2 + minimumDotSpacingPx + 8;
        if (fiducialsEnabled && borderPx < minimumMarkerBorder) {
            throw new IllegalArgumentException(
                    "borderPx is too small for the requested dots and orientation fiducials; "
                            + "minimum is " + minimumMarkerBorder);
        }

        int activeWidth = widthPx - 2 * borderPx;
        int activeHeight = heightPx - 2 * borderPx;
        int pitch = cellPitchPx(dotDiameterPx, minimumDotSpacingPx);
        int columns = activeWidth / pitch;
        int rows = activeHeight / pitch;
        if (columns < 4 || rows < 4) {
            throw new IllegalArgumentException(
                    "active region is too small for a spatially distributed dot field");
        }
        int candidateCount = Math.multiplyExact(columns, rows);
        double nominalDotArea = Math.PI * square(dotDiameterPx / 2.0);
        int requestedDots = (int) Math.round(targetFillRatio * activeWidth * activeHeight / nominalDotArea);
        if (requestedDots < 1 || requestedDots > candidateCount) {
            double maximumAchievable = candidateCount * nominalDotArea / (activeWidth * activeHeight);
            throw new IllegalArgumentException(
                    "targetFillRatio cannot be achieved with the selected dot diameter and spacing; "
                            + "maximum is approximately " + maximumAchievable);
        }
    }

    public static BosTargetParameters defaults() {
        return new BosTargetParameters(
                1600,
                1000,
                150.0,
                20260823L,
                7,
                0.12,
                3,
                64,
                true,
                false);
    }

    public int activeWidthPx() {
        return widthPx - 2 * borderPx;
    }

    public int activeHeightPx() {
        return heightPx - 2 * borderPx;
    }

    public double intendedWidthMm() {
        return widthPx * 25.4 / intendedDpi;
    }

    public double intendedHeightMm() {
        return heightPx * 25.4 / intendedDpi;
    }

    static int jitterRadiusPx(int dotDiameterPx) {
        return Math.max(1, dotDiameterPx / 3);
    }

    static int cellPitchPx(int dotDiameterPx, int minimumDotSpacingPx) {
        return dotDiameterPx
                + minimumDotSpacingPx
                + 2 * jitterRadiusPx(dotDiameterPx);
    }

    private static double square(double value) {
        return value * value;
    }
}

package org.fresnel.optics;

/**
 * Physical definition of one orientation-selectable variable-line grating sheet.
 * Exactly one family of parallel lines is represented by {@link #lineOrientation()}.
 */
public record VariableLineGratingParameters(
        double widthMm,
        double heightMm,
        LineOrientation lineOrientation,
        double startPitchUm,
        double endPitchUm,
        GratingProgression progression,
        ProgressionDirection progressionDirection,
        double dutyCycle,
        double phaseOffsetCycles,
        Polarity polarity,
        double marginMm,
        double annotationSizeMm,
        boolean showAxis,
        AxisQuantity axisQuantity,
        int tickCount,
        boolean showReferenceBands,
        double referenceBandSizeMm,
        double dpi
) {
    public static final long MAX_RASTER_PIXELS = 80_000_000L;

    public VariableLineGratingParameters {
        requireFinitePositive(widthMm, "widthMm");
        requireFinitePositive(heightMm, "heightMm");
        requireFinitePositive(startPitchUm, "startPitchUm");
        requireFinitePositive(endPitchUm, "endPitchUm");
        requireFinitePositive(dpi, "dpi");
        requireFiniteNonNegative(marginMm, "marginMm");
        requireFiniteNonNegative(annotationSizeMm, "annotationSizeMm");
        requireFiniteNonNegative(referenceBandSizeMm, "referenceBandSizeMm");
        if (lineOrientation == null) throw new IllegalArgumentException("lineOrientation must not be null");
        if (progression == null) throw new IllegalArgumentException("progression must not be null");
        if (progressionDirection == null) throw new IllegalArgumentException("progressionDirection must not be null");
        if (polarity == null) throw new IllegalArgumentException("polarity must not be null");
        if (axisQuantity == null) throw new IllegalArgumentException("axisQuantity must not be null");
        if (!Double.isFinite(dutyCycle) || dutyCycle <= 0.0 || dutyCycle >= 1.0) {
            throw new IllegalArgumentException("dutyCycle must be finite and between 0 and 1");
        }
        if (!Double.isFinite(phaseOffsetCycles)) {
            throw new IllegalArgumentException("phaseOffsetCycles must be finite");
        }
        if (tickCount < 2 || tickCount > 21) {
            throw new IllegalArgumentException("tickCount must be between 2 and 21");
        }
        if (showAxis && annotationSizeMm <= 0.0) {
            throw new IllegalArgumentException("annotationSizeMm must be > 0 when showAxis is enabled");
        }
        VariableLineGratingModel.Layout layout = VariableLineGratingModel.layoutOf(
                widthMm, heightMm, lineOrientation, marginMm, showAxis, annotationSizeMm);
        if (showReferenceBands && referenceBandSizeMm <= 0.0) {
            throw new IllegalArgumentException(
                    "referenceBandSizeMm must be > 0 when showReferenceBands is enabled");
        }
        double orthogonalExtent = lineOrientation == LineOrientation.VERTICAL
                ? layout.activeHeightMm()
                : layout.activeWidthMm();
        if (showReferenceBands && 2.0 * referenceBandSizeMm >= orthogonalExtent) {
            throw new IllegalArgumentException(
                    "reference bands must leave a non-empty variable-grating region");
        }
        long widthPx = Math.max(1L, Math.round(widthMm / Units.pixelSizeMm(dpi)));
        long heightPx = Math.max(1L, Math.round(heightMm / Units.pixelSizeMm(dpi)));
        if (widthPx > Integer.MAX_VALUE || heightPx > Integer.MAX_VALUE
                || widthPx * heightPx > MAX_RASTER_PIXELS) {
            throw new IllegalArgumentException(
                    "requested raster exceeds the safe " + MAX_RASTER_PIXELS + " pixel limit");
        }
    }

    public static VariableLineGratingParameters defaults() {
        return new VariableLineGratingParameters(
                190.0,
                277.0,
                LineOrientation.VERTICAL,
                500.0,
                40.0,
                GratingProgression.LINEAR_SPATIAL_FREQUENCY,
                ProgressionDirection.NORMAL,
                0.5,
                0.0,
                Polarity.POSITIVE,
                5.0,
                14.0,
                true,
                AxisQuantity.PITCH_UM,
                9,
                true,
                5.0,
                300.0);
    }

    public double activeProgressionLengthMm() {
        VariableLineGratingModel.Layout layout = VariableLineGratingModel.layout(this);
        return lineOrientation == LineOrientation.VERTICAL
                ? layout.activeWidthMm()
                : layout.activeHeightMm();
    }

    private static void requireFinitePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and > 0");
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0");
        }
    }
}

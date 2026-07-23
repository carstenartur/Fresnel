package org.fresnel.optics;

import java.util.ArrayList;
import java.util.List;

/** Deterministic physical-coordinate model shared by every grating output path. */
public final class VariableLineGratingModel {

    private static final double EPS = 1e-12;

    private VariableLineGratingModel() {}

    /** Physical sheet and active-area bounds, all in millimetres. */
    public record Layout(
            double sheetWidthMm,
            double sheetHeightMm,
            double activeXmm,
            double activeYmm,
            double activeWidthMm,
            double activeHeightMm,
            double axisCoordinateMm,
            boolean verticalAxisLayout
    ) {}

    /** One opaque interval along the coordinate perpendicular to the line family. */
    public record Interval(double startMm, double endMm) {
        public Interval {
            if (!Double.isFinite(startMm) || !Double.isFinite(endMm) || endMm < startMm) {
                throw new IllegalArgumentException("invalid grating interval");
            }
        }
    }

    public static Layout layout(VariableLineGratingParameters p) {
        return layoutOf(
                p.widthMm(), p.heightMm(), p.lineOrientation(), p.marginMm(),
                p.showAxis(), p.annotationSizeMm());
    }

    static Layout layoutOf(
            double widthMm,
            double heightMm,
            LineOrientation orientation,
            double marginMm,
            boolean showAxis,
            double annotationSizeMm) {
        double axisSpace = showAxis ? annotationSizeMm : 0.0;
        double activeWidth = widthMm - 2.0 * marginMm
                - (orientation == LineOrientation.HORIZONTAL ? axisSpace : 0.0);
        double activeHeight = heightMm - 2.0 * marginMm
                - (orientation == LineOrientation.VERTICAL ? axisSpace : 0.0);
        if (activeWidth <= 0.0 || activeHeight <= 0.0) {
            throw new IllegalArgumentException(
                    "margins and annotation area leave no active grating region");
        }
        double axisCoordinate = orientation == LineOrientation.VERTICAL
                ? marginMm + activeHeight + axisSpace * 0.25
                : marginMm + activeWidth + axisSpace * 0.25;
        return new Layout(
                widthMm,
                heightMm,
                marginMm,
                marginMm,
                activeWidth,
                activeHeight,
                axisCoordinate,
                orientation == LineOrientation.HORIZONTAL);
    }

    /** Local pitch at normalized configured progression coordinate {@code u}. */
    public static double pitchMmAtNormalized(VariableLineGratingParameters p, double u) {
        double clamped = clamp01(u);
        double directed = p.progressionDirection() == ProgressionDirection.NORMAL
                ? clamped
                : 1.0 - clamped;
        double p0 = p.startPitchUm() / 1000.0;
        double p1 = p.endPitchUm() / 1000.0;
        return switch (p.progression()) {
            case LINEAR_PITCH -> p0 + (p1 - p0) * directed;
            case LINEAR_SPATIAL_FREQUENCY -> {
                double f0 = 1.0 / p0;
                double f1 = 1.0 / p1;
                yield 1.0 / (f0 + (f1 - f0) * directed);
            }
            case LOGARITHMIC_PITCH -> p0 * Math.pow(p1 / p0, directed);
        };
    }

    /** Local pitch at physical progression coordinate {@code sMm}. */
    public static double pitchMmAt(VariableLineGratingParameters p, double sMm) {
        double length = p.activeProgressionLengthMm();
        return pitchMmAtNormalized(p, length == 0.0 ? 0.0 : sMm / length);
    }

    /**
     * Accumulated spatial cycles from the start of the active progression extent.
     * This is the integral of {@code 1 / pitch(s)}, plus the configured phase offset.
     */
    public static double cyclesAt(VariableLineGratingParameters p, double sMm) {
        double length = p.activeProgressionLengthMm();
        double u = clamp01(sMm / length);
        double integral = p.progressionDirection() == ProgressionDirection.NORMAL
                ? primitiveCycles(p, u)
                : primitiveCycles(p, 1.0) - primitiveCycles(p, 1.0 - u);
        return p.phaseOffsetCycles() + length * integral;
    }

    /** Number of complete/fractional periods over the active extent, excluding phase offset. */
    public static double nominalCycleCount(VariableLineGratingParameters p) {
        return cyclesAt(p, p.activeProgressionLengthMm()) - p.phaseOffsetCycles();
    }

    /** Whether the physical point is opaque. Points outside the active grating are clear. */
    public static boolean isOpaque(VariableLineGratingParameters p, double xMm, double yMm) {
        Layout layout = layout(p);
        if (xMm < layout.activeXmm() || xMm >= layout.activeXmm() + layout.activeWidthMm()
                || yMm < layout.activeYmm() || yMm >= layout.activeYmm() + layout.activeHeightMm()) {
            return false;
        }

        double progressionS = p.lineOrientation() == LineOrientation.VERTICAL
                ? xMm - layout.activeXmm()
                : yMm - layout.activeYmm();
        double cycles;
        if (inStartReferenceBand(p, layout, xMm, yMm)) {
            cycles = p.phaseOffsetCycles()
                    + progressionS / pitchMmAtNormalized(p, 0.0);
        } else if (inEndReferenceBand(p, layout, xMm, yMm)) {
            cycles = p.phaseOffsetCycles()
                    + progressionS / pitchMmAtNormalized(p, 1.0);
        } else {
            cycles = cyclesAt(p, progressionS);
        }
        double fractional = cycles - Math.floor(cycles);
        boolean positiveOpaque = fractional < p.dutyCycle();
        return p.polarity() == Polarity.POSITIVE ? positiveOpaque : !positiveOpaque;
    }

    /** Opaque intervals of the variable progression for vector export. */
    public static List<Interval> opaqueIntervals(VariableLineGratingParameters p) {
        double length = p.activeProgressionLengthMm();
        double startCycles = cyclesAt(p, 0.0);
        double endCycles = cyclesAt(p, length);
        List<Interval> result = new ArrayList<>();

        long firstCycle = (long) Math.floor(startCycles) - 1L;
        long lastCycle = (long) Math.ceil(endCycles) + 1L;
        for (long cycle = firstCycle; cycle <= lastCycle; cycle++) {
            double positiveStart = cycle;
            double positiveEnd = cycle + p.dutyCycle();
            if (p.polarity() == Polarity.POSITIVE) {
                appendClipped(result, p, positiveStart, positiveEnd, startCycles, endCycles, length);
            } else {
                appendClipped(result, p, cycle + p.dutyCycle(), cycle + 1.0,
                        startCycles, endCycles, length);
            }
        }
        return List.copyOf(result);
    }

    /** Constant-pitch opaque intervals for one optional reference band. */
    public static List<Interval> constantPitchOpaqueIntervals(
            VariableLineGratingParameters p,
            double pitchMm) {
        double length = p.activeProgressionLengthMm();
        double startCycles = p.phaseOffsetCycles();
        double endCycles = startCycles + length / pitchMm;
        List<Interval> result = new ArrayList<>();
        long firstCycle = (long) Math.floor(startCycles) - 1L;
        long lastCycle = (long) Math.ceil(endCycles) + 1L;
        for (long cycle = firstCycle; cycle <= lastCycle; cycle++) {
            double a = p.polarity() == Polarity.POSITIVE
                    ? cycle
                    : cycle + p.dutyCycle();
            double b = p.polarity() == Polarity.POSITIVE
                    ? cycle + p.dutyCycle()
                    : cycle + 1.0;
            double start = Math.max(0.0, (a - startCycles) * pitchMm);
            double end = Math.min(length, (b - startCycles) * pitchMm);
            if (end > start + EPS) result.add(new Interval(start, end));
        }
        return List.copyOf(result);
    }

    /** Position at which an accumulated-cycle target is reached. */
    public static double positionForCycles(VariableLineGratingParameters p, double targetCycles) {
        double length = p.activeProgressionLengthMm();
        double min = cyclesAt(p, 0.0);
        double max = cyclesAt(p, length);
        if (targetCycles <= min) return 0.0;
        if (targetCycles >= max) return length;
        double lo = 0.0;
        double hi = length;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) * 0.5;
            if (cyclesAt(p, mid) < targetCycles) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5;
    }

    public static boolean inStartReferenceBand(
            VariableLineGratingParameters p, Layout layout, double xMm, double yMm) {
        if (!p.showReferenceBands()) return false;
        return p.lineOrientation() == LineOrientation.VERTICAL
                ? yMm < layout.activeYmm() + p.referenceBandSizeMm()
                : xMm < layout.activeXmm() + p.referenceBandSizeMm();
    }

    public static boolean inEndReferenceBand(
            VariableLineGratingParameters p, Layout layout, double xMm, double yMm) {
        if (!p.showReferenceBands()) return false;
        return p.lineOrientation() == LineOrientation.VERTICAL
                ? yMm >= layout.activeYmm() + layout.activeHeightMm() - p.referenceBandSizeMm()
                : xMm >= layout.activeXmm() + layout.activeWidthMm() - p.referenceBandSizeMm();
    }

    private static void appendClipped(
            List<Interval> target,
            VariableLineGratingParameters p,
            double cycleStart,
            double cycleEnd,
            double minCycles,
            double maxCycles,
            double length) {
        double clippedStart = Math.max(minCycles, cycleStart);
        double clippedEnd = Math.min(maxCycles, cycleEnd);
        if (clippedEnd <= clippedStart + EPS) return;
        double start = positionForCycles(p, clippedStart);
        double end = positionForCycles(p, clippedEnd);
        if (end > start + EPS && start < length) {
            target.add(new Interval(Math.max(0.0, start), Math.min(length, end)));
        }
    }

    /** Integral from 0 to u of the reciprocal pitch, expressed in cycles/mm. */
    private static double primitiveCycles(VariableLineGratingParameters p, double u) {
        double p0 = p.startPitchUm() / 1000.0;
        double p1 = p.endPitchUm() / 1000.0;
        return switch (p.progression()) {
            case LINEAR_PITCH -> {
                double d = p1 - p0;
                yield Math.abs(d) < EPS
                        ? u / p0
                        : Math.log((p0 + d * u) / p0) / d;
            }
            case LINEAR_SPATIAL_FREQUENCY -> {
                double f0 = 1.0 / p0;
                double f1 = 1.0 / p1;
                yield f0 * u + 0.5 * (f1 - f0) * u * u;
            }
            case LOGARITHMIC_PITCH -> {
                double a = Math.log(p1 / p0);
                yield Math.abs(a) < EPS
                        ? u / p0
                        : -Math.expm1(-a * u) / (p0 * a);
            }
        };
    }

    private static double clamp01(double value) {
        if (value <= 0.0) return 0.0;
        if (value >= 1.0) return 1.0;
        return value;
    }
}

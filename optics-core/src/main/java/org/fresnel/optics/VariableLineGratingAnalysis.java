package org.fresnel.optics;

import java.util.ArrayList;
import java.util.List;

/** Orientation-aware sampling and fabrication metrics for a variable-line grating. */
public final class VariableLineGratingAnalysis {

    public static final List<Double> DOT_THRESHOLDS = List.of(8.0, 4.0, 3.0, 2.0);

    private VariableLineGratingAnalysis() {}

    public record ThresholdCrossing(
            double dotsPerPeriod,
            boolean crossed,
            Double positionMm,
            Double normalizedPosition,
            Double pitchUm
    ) {}

    public record Result(
            LineOrientation lineOrientation,
            DeviceAxis testedDeviceAxis,
            double selectedAxisDpi,
            double minPitchUm,
            double maxPitchUm,
            double minimumOpaqueFeatureUm,
            double minimumClearFeatureUm,
            double minDotsPerPeriod,
            double maxDotsPerPeriod,
            double minDotsPerOpaqueFeature,
            double minDotsPerClearFeature,
            double nominalCycleCount,
            List<ThresholdCrossing> thresholdCrossings
    ) {
        public Result {
            thresholdCrossings = List.copyOf(thresholdCrossings);
        }
    }

    public static Result analyze(VariableLineGratingParameters p) {
        return analyze(p, null);
    }

    public static Result analyze(
            VariableLineGratingParameters p,
            PrinterRasterProfile profile) {
        DeviceAxis testedAxis = profile == null
                ? (p.lineOrientation() == LineOrientation.VERTICAL ? DeviceAxis.X : DeviceAxis.Y)
                : profile.testedDeviceAxis(p.lineOrientation());
        double dpi = profile == null ? p.dpi() : profile.dpiForTestedAxis(p.lineOrientation());
        double startPitchUm = VariableLineGratingModel.pitchMmAtNormalized(p, 0.0) * 1000.0;
        double endPitchUm = VariableLineGratingModel.pitchMmAtNormalized(p, 1.0) * 1000.0;
        double minPitchUm = Math.min(startPitchUm, endPitchUm);
        double maxPitchUm = Math.max(startPitchUm, endPitchUm);
        double dotsPerUm = dpi / (Units.INCH_MM * 1000.0);

        List<ThresholdCrossing> crossings = new ArrayList<>();
        for (double threshold : DOT_THRESHOLDS) {
            double thresholdPitchUm = threshold / dotsPerUm;
            double u = findPitchCrossing(p, thresholdPitchUm);
            crossings.add(new ThresholdCrossing(
                    threshold,
                    Double.isFinite(u),
                    Double.isFinite(u) ? u * p.activeProgressionLengthMm() : null,
                    Double.isFinite(u) ? u : null,
                    Double.isFinite(u) ? thresholdPitchUm : null));
        }

        return new Result(
                p.lineOrientation(),
                testedAxis,
                dpi,
                minPitchUm,
                maxPitchUm,
                minPitchUm * p.dutyCycle(),
                minPitchUm * (1.0 - p.dutyCycle()),
                minPitchUm * dotsPerUm,
                maxPitchUm * dotsPerUm,
                minPitchUm * p.dutyCycle() * dotsPerUm,
                minPitchUm * (1.0 - p.dutyCycle()) * dotsPerUm,
                VariableLineGratingModel.nominalCycleCount(p),
                crossings);
    }

    private static double findPitchCrossing(VariableLineGratingParameters p, double targetPitchUm) {
        double a = VariableLineGratingModel.pitchMmAtNormalized(p, 0.0) * 1000.0;
        double b = VariableLineGratingModel.pitchMmAtNormalized(p, 1.0) * 1000.0;
        double min = Math.min(a, b);
        double max = Math.max(a, b);
        if (targetPitchUm < min || targetPitchUm > max) return Double.NaN;
        if (Math.abs(a - b) < 1e-12) return Math.abs(targetPitchUm - a) < 1e-9 ? 0.0 : Double.NaN;

        boolean increasing = b > a;
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) * 0.5;
            double value = VariableLineGratingModel.pitchMmAtNormalized(p, mid) * 1000.0;
            if ((value < targetPitchUm) == increasing) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5;
    }
}

package org.fresnel.optics;

import java.util.List;

/**
 * Parameters for a multi-point / line focus zone plate. The aperture is divided
 * into N sub-apertures (one per focus target) by a deterministic per-pixel hash;
 * each sub-aperture diffracts toward its own (xf, yf, zf). This produces N distinct
 * focal spots; for line focus, supply many targets along a line.
 *
 * @param apertureDiameterMm aperture diameter, mm
 * @param focusPoints        list of focus targets {@code (x, y, z)} in mm relative
 *                           to the centre of the aperture (z is the focal distance)
 * @param wavelengthNm       wavelength, nm
 * @param dpi                printer DPI
 * @param maskType           binary amplitude or greyscale phase
 * @param polarity           polarity
 */
public record MultiFocusParameters(
        double apertureDiameterMm,
        List<FocusPoint> focusPoints,
        double wavelengthNm,
        double dpi,
        MaskType maskType,
        Polarity polarity
) {

    /** A single (x, y, z) focus target in mm. */
    public record FocusPoint(double xMm, double yMm, double zMm) {
        public FocusPoint {
            if (zMm <= 0) throw new IllegalArgumentException("zMm must be > 0");
        }
    }

    public MultiFocusParameters {
        if (apertureDiameterMm <= 0) throw new IllegalArgumentException("apertureDiameterMm must be > 0");
        if (focusPoints == null || focusPoints.isEmpty())
            throw new IllegalArgumentException("focusPoints must not be empty");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0");
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (polarity == null) throw new IllegalArgumentException("polarity must not be null");
        focusPoints = List.copyOf(focusPoints);
    }

    /** Build a line-focus design with {@code n} equally-spaced points between two endpoints. */
    public static List<FocusPoint> lineOfPoints(
            double x0, double y0, double z0,
            double x1, double y1, double z1, int n) {
        if (n < 2) throw new IllegalArgumentException("n must be ≥ 2");
        List<FocusPoint> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            out.add(new FocusPoint(
                    x0 + t * (x1 - x0),
                    y0 + t * (y1 - y0),
                    z0 + t * (z1 - z0)));
        }
        return out;
    }
}

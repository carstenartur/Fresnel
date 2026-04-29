package org.fresnel.optics;

/**
 * Unit conversion helpers for optical design.
 * <p>
 * The optics core uses millimeters for lengths, nanometers for wavelengths
 * and DPI (dots-per-inch) for printer resolution.
 */
public final class Units {

    /** Inch in millimeters. */
    public static final double INCH_MM = 25.4;

    private Units() {}

    /** Pixel size in millimeters for the given DPI. */
    public static double pixelSizeMm(double dpi) {
        if (dpi <= 0) {
            throw new IllegalArgumentException("DPI must be positive");
        }
        return INCH_MM / dpi;
    }

    /** Pixel size in micrometers for the given DPI. */
    public static double pixelSizeMicrons(double dpi) {
        return pixelSizeMm(dpi) * 1000.0;
    }

    /** Convert millimeters to pixels at the given DPI (rounded). */
    public static int mmToPixels(double mm, double dpi) {
        return (int) Math.round(mm / pixelSizeMm(dpi));
    }

    /** Convert nanometers to millimeters. */
    public static double nmToMm(double nm) {
        return nm * 1.0e-6;
    }
}

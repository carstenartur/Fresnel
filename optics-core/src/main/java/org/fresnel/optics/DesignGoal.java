package org.fresnel.optics;

/**
 * User-provided goal for the Optical Design Assistant.
 *
 * <p>All lengths are in millimetres; wavelength is in nanometres; DPI is dots per inch.
 *
 * @param dpi              printer resolution (dots per inch)
 * @param pageSizeWidthMm  printable page width in millimetres
 * @param pageSizeHeightMm printable page height in millimetres
 * @param wavelengthNm     design wavelength in nanometres (e.g. 532 for green laser)
 * @param targetFocusMm    desired focal distance in millimetres
 * @param maxApertureMm    optional hard cap on aperture diameter (null = no additional constraint)
 */
public record DesignGoal(
        double dpi,
        double pageSizeWidthMm,
        double pageSizeHeightMm,
        double wavelengthNm,
        double targetFocusMm,
        Double maxApertureMm
) {
    public DesignGoal {
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        if (pageSizeWidthMm <= 0) throw new IllegalArgumentException("pageSizeWidthMm must be > 0");
        if (pageSizeHeightMm <= 0) throw new IllegalArgumentException("pageSizeHeightMm must be > 0");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0");
        if (targetFocusMm <= 0) throw new IllegalArgumentException("targetFocusMm must be > 0");
        if (maxApertureMm != null && maxApertureMm <= 0)
            throw new IllegalArgumentException("maxApertureMm must be > 0");
    }
}

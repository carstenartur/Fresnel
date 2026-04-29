package org.fresnel.optics;

/**
 * Parameters for a single Fresnel zone plate.
 *
 * @param apertureDiameterMm aperture diameter in millimeters
 * @param focalLengthMm      design focal length in millimeters
 * @param wavelengthNm       design wavelength in nanometers
 * @param dpi                printer resolution (dots per inch)
 * @param targetOffsetXmm    off-axis target offset X (mm), 0 for on-axis
 * @param targetOffsetYmm    off-axis target offset Y (mm), 0 for on-axis
 * @param maskType           binary amplitude or greyscale phase
 * @param polarity           which half of the cosine is transparent
 */
public record SingleZonePlateParameters(
        double apertureDiameterMm,
        double focalLengthMm,
        double wavelengthNm,
        double dpi,
        double targetOffsetXmm,
        double targetOffsetYmm,
        MaskType maskType,
        Polarity polarity
) {

    public SingleZonePlateParameters {
        if (apertureDiameterMm <= 0) throw new IllegalArgumentException("apertureDiameterMm must be > 0");
        if (focalLengthMm <= 0) throw new IllegalArgumentException("focalLengthMm must be > 0");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0");
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (polarity == null) throw new IllegalArgumentException("polarity must not be null");
    }

    /** On-axis convenience constructor. */
    public static SingleZonePlateParameters onAxis(
            double apertureDiameterMm,
            double focalLengthMm,
            double wavelengthNm,
            double dpi) {
        return new SingleZonePlateParameters(
                apertureDiameterMm, focalLengthMm, wavelengthNm, dpi,
                0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
    }
}

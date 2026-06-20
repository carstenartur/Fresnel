package org.fresnel.optics;

/**
 * Parameters for converting a phase mask into a physical surface relief.
 *
 * @param wavelengthNm          illumination wavelength in nanometres
 * @param refractiveIndexDelta  refractive index difference Δn between relief material and environment
 * @param maxPhaseShiftRad      phase shift represented by greyscale value 255 (typically 2π)
 */
public record ReliefParameters(
        double wavelengthNm,
        double refractiveIndexDelta,
        double maxPhaseShiftRad
) {
    public ReliefParameters {
        if (!(wavelengthNm > 0.0) || !Double.isFinite(wavelengthNm))
            throw new IllegalArgumentException("wavelengthNm must be finite and > 0");
        if (!(refractiveIndexDelta > 0.0) || !Double.isFinite(refractiveIndexDelta))
            throw new IllegalArgumentException("refractiveIndexDelta must be finite and > 0");
        if (!(maxPhaseShiftRad > 0.0) || !Double.isFinite(maxPhaseShiftRad))
            throw new IllegalArgumentException("maxPhaseShiftRad must be finite and > 0");
    }
}

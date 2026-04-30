package org.fresnel.optics;

/**
 * Parameters for a hexagonal macro cell composed of many small sub-zone-plates,
 * all converging on a common image plane.
 *
 * <p>The macro cell is a regular hexagon with flat-top orientation and circumscribed
 * radius {@code macroRadiusMm} (so the flat-to-flat distance is {@code √3 · macroRadiusMm}).
 * Sub-elements are circular zone plates of diameter {@code subDiameterMm} packed on a
 * triangular (hex) lattice with center-to-center pitch {@code subPitchMm}
 * (must be ≥ {@code subDiameterMm}). Sub-elements whose centre lies inside the hex
 * are kept; their support is clipped to both the sub-circle and the outer hex.
 *
 * <p>All sub-plates focus on a common target {@code (targetX, targetY, focal)} measured
 * from the macro-cell centre. For each sub-plate centred at {@code (cx, cy)} the local
 * off-axis offset is {@code (targetX - cx, targetY - cy)} so they constructively project
 * the same point on the image plane.
 *
 * @param macroRadiusMm     circumscribed radius of the outer hex (centre → vertex), mm
 * @param subDiameterMm     diameter of each sub-zone-plate, mm
 * @param subPitchMm        centre-to-centre spacing on the hex lattice, mm (≥ subDiameterMm)
 * @param focalLengthMm     z-distance from macro plane to common image plane, mm
 * @param targetOffsetXmm   in-plane X target offset from macro centre, mm
 * @param targetOffsetYmm   in-plane Y target offset from macro centre, mm
 * @param wavelengthNm      design wavelength, nm
 * @param dpi               printer resolution
 * @param maskType          binary amplitude or greyscale phase
 * @param polarity          mask polarity
 */
public record HexMacroCellParameters(
        double macroRadiusMm,
        double subDiameterMm,
        double subPitchMm,
        double focalLengthMm,
        double targetOffsetXmm,
        double targetOffsetYmm,
        double wavelengthNm,
        double dpi,
        MaskType maskType,
        Polarity polarity
) {

    public HexMacroCellParameters {
        if (macroRadiusMm <= 0) throw new IllegalArgumentException("macroRadiusMm must be > 0");
        if (subDiameterMm <= 0) throw new IllegalArgumentException("subDiameterMm must be > 0");
        if (subPitchMm < subDiameterMm)
            throw new IllegalArgumentException("subPitchMm must be ≥ subDiameterMm");
        if (subDiameterMm > 2.0 * macroRadiusMm)
            throw new IllegalArgumentException("subDiameterMm must be ≤ 2·macroRadiusMm");
        if (focalLengthMm <= 0) throw new IllegalArgumentException("focalLengthMm must be > 0");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0");
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (polarity == null) throw new IllegalArgumentException("polarity must not be null");
    }

    /** Convenience constructor with on-axis target and binary positive mask. */
    public static HexMacroCellParameters onAxis(
            double macroRadiusMm,
            double subDiameterMm,
            double subPitchMm,
            double focalLengthMm,
            double wavelengthNm,
            double dpi) {
        return new HexMacroCellParameters(
                macroRadiusMm, subDiameterMm, subPitchMm,
                focalLengthMm, 0.0, 0.0,
                wavelengthNm, dpi,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
    }
}

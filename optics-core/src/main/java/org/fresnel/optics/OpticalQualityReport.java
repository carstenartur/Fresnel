package org.fresnel.optics;

/**
 * Optical quality report for a Fresnel zone plate design.
 *
 * <p>All formulas assume a paraxial diffractive optic working in air (n&nbsp;=&nbsp;1).
 *
 * <h2>Formulas and assumptions</h2>
 * <dl>
 *   <dt>Numerical aperture (NA)</dt>
 *   <dd>{@code NA = D / (2·f)} &mdash; paraxial, sin&nbsp;&theta;&nbsp;&asymp;&nbsp;&theta;&nbsp;=&nbsp;D/(2f)</dd>
 *   <dt>f-number (F#)</dt>
 *   <dd>{@code F# = f / D}</dd>
 *   <dt>Airy disk diameter</dt>
 *   <dd>{@code d_Airy = 2.44·&lambda;·F# = 2.44·&lambda;·f/D}
 *       &mdash; diameter to the first dark ring of the Airy pattern</dd>
 *   <dt>Rayleigh angular resolution</dt>
 *   <dd>{@code &theta;_R = 1.22·&lambda;/D} (radians) &mdash; classical Rayleigh criterion</dd>
 *   <dt>Depth of focus (DoF)</dt>
 *   <dd>{@code DoF = 2·&lambda;·F#² = 2·&lambda;·(f/D)²}
 *       &mdash; &plusmn;1&lambda; wave-front-error criterion</dd>
 *   <dt>Outermost zone width</dt>
 *   <dd>{@code &Delta;r = &lambda;·f/D} &mdash; paraxial approximation for the outermost zone</dd>
 *   <dt>Chromatic focal shift</dt>
 *   <dd>{@code &Delta;f = f_design·(&lambda;_design/&lambda;_min &minus; &lambda;_design/&lambda;_max)}
 *       &mdash; derived from the diffractive relation f(&lambda;)&nbsp;&prop;&nbsp;1/&lambda;</dd>
 * </dl>
 *
 * @param wavelengthNm                   design wavelength (nm)
 * @param focalLengthMm                  design focal length (mm)
 * @param apertureDiameterMm             aperture diameter (mm)
 * @param numericalAperture              dimensionless NA = D / (2·f)
 * @param fNumber                        dimensionless f-number = f / D
 * @param airyDiskDiameterMicrons        estimated Airy disk diameter (&mu;m)
 * @param rayleighAngularResolutionRad   Rayleigh angular resolution (rad)
 * @param depthOfFocusMicrons            depth-of-focus estimate (&mu;m)
 * @param outermostZoneWidthMicrons      outermost zone width (&mu;m), same as &Delta;r = &lambda;·f/D
 * @param chromaticFocalShiftMm          chromatic focal shift (mm) over the given wavelength range
 * @param chromaticRangeMinNm            minimum wavelength used for chromatic shift estimate (nm)
 * @param chromaticRangeMaxNm            maximum wavelength used for chromatic shift estimate (nm)
 */
public record OpticalQualityReport(
        double wavelengthNm,
        double focalLengthMm,
        double apertureDiameterMm,
        double numericalAperture,
        double fNumber,
        double airyDiskDiameterMicrons,
        double rayleighAngularResolutionRad,
        double depthOfFocusMicrons,
        double outermostZoneWidthMicrons,
        double chromaticFocalShiftMm,
        double chromaticRangeMinNm,
        double chromaticRangeMaxNm
) {

    /** Default minimum wavelength (nm) for the visible-range chromatic shift estimate. */
    public static final double DEFAULT_CHROMATIC_MIN_NM = 450.0;
    /** Default maximum wavelength (nm) for the visible-range chromatic shift estimate. */
    public static final double DEFAULT_CHROMATIC_MAX_NM = 650.0;
}

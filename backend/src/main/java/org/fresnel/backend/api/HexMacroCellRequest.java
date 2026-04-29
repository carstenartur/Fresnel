package org.fresnel.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.HexMacroCellParameters;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.Polarity;

/** REST request body for a hexagonal macro cell design. */
public record HexMacroCellRequest(
        @NotNull @Positive Double macroRadiusMm,
        @NotNull @Positive Double subDiameterMm,
        @NotNull @Positive Double subPitchMm,
        @NotNull @Positive Double focalLengthMm,
        Double targetOffsetXmm,
        Double targetOffsetYmm,
        @NotNull @Positive Double wavelengthNm,
        @NotNull @Positive Double dpi,
        MaskType maskType,
        Polarity polarity
) {
    public HexMacroCellParameters toParameters() {
        return new HexMacroCellParameters(
                macroRadiusMm, subDiameterMm, subPitchMm,
                focalLengthMm,
                targetOffsetXmm == null ? 0.0 : targetOffsetXmm,
                targetOffsetYmm == null ? 0.0 : targetOffsetYmm,
                wavelengthNm, dpi,
                maskType == null ? MaskType.BINARY_AMPLITUDE : maskType,
                polarity == null ? Polarity.POSITIVE : polarity);
    }
}

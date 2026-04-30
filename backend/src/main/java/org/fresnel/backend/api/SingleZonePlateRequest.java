package org.fresnel.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.Polarity;
import org.fresnel.optics.SingleZonePlateParameters;

/**
 * REST request body for a single Fresnel zone plate design.
 */
public record SingleZonePlateRequest(
        @NotNull @Positive Double apertureDiameterMm,
        @NotNull @Positive Double focalLengthMm,
        @NotNull @Positive Double wavelengthNm,
        @NotNull @Positive Double dpi,
        Double targetOffsetXmm,
        Double targetOffsetYmm,
        MaskType maskType,
        Polarity polarity
) {

    public SingleZonePlateParameters toParameters() {
        return new SingleZonePlateParameters(
                apertureDiameterMm,
                focalLengthMm,
                wavelengthNm,
                dpi,
                targetOffsetXmm == null ? 0.0 : targetOffsetXmm,
                targetOffsetYmm == null ? 0.0 : targetOffsetYmm,
                maskType == null ? MaskType.BINARY_AMPLITUDE : maskType,
                polarity == null ? Polarity.POSITIVE : polarity);
    }
}

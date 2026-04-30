package org.fresnel.backend.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.MultiFocusParameters;
import org.fresnel.optics.Polarity;

import java.util.ArrayList;
import java.util.List;

/** REST request body for a multi-point / line focus design. */
public record MultiFocusRequest(
        @NotNull @Positive Double apertureDiameterMm,
        @NotEmpty List<FocusPointDto> focusPoints,
        @NotNull @Positive Double wavelengthNm,
        @NotNull @Positive Double dpi,
        MaskType maskType,
        Polarity polarity
) {
    public record FocusPointDto(
            @NotNull Double xMm,
            @NotNull Double yMm,
            @NotNull @Positive Double zMm
    ) {
        public MultiFocusParameters.FocusPoint toCore() {
            return new MultiFocusParameters.FocusPoint(xMm, yMm, zMm);
        }
    }

    public MultiFocusParameters toParameters() {
        List<MultiFocusParameters.FocusPoint> pts = new ArrayList<>();
        for (FocusPointDto d : focusPoints) pts.add(d.toCore());
        return new MultiFocusParameters(
                apertureDiameterMm, pts, wavelengthNm, dpi,
                maskType == null ? MaskType.BINARY_AMPLITUDE : maskType,
                polarity == null ? Polarity.POSITIVE : polarity);
    }
}

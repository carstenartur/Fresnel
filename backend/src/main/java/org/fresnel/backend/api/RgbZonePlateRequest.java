package org.fresnel.backend.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** REST request body for an RGB / multi-wavelength zone plate. */
public record RgbZonePlateRequest(
        @Valid @NotNull SingleZonePlateRequest base,
        @NotNull @Positive Double redNm,
        @NotNull @Positive Double greenNm,
        @NotNull @Positive Double blueNm
) {}

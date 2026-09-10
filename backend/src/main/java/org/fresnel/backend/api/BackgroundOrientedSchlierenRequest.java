package org.fresnel.backend.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.fresnel.optics.BackgroundOrientedSchlierenParameters;

/** REST and job-file body for a deterministic BOS random-dot target. */
public record BackgroundOrientedSchlierenRequest(
        @NotNull @Min(BackgroundOrientedSchlierenParameters.MIN_SIDE_PX)
        @Max(BackgroundOrientedSchlierenParameters.MAX_SIDE_PX) Integer widthPx,
        @NotNull @Min(BackgroundOrientedSchlierenParameters.MIN_SIDE_PX)
        @Max(BackgroundOrientedSchlierenParameters.MAX_SIDE_PX) Integer heightPx,
        @NotNull @DecimalMin("50") @DecimalMax("2400") Double intendedDpi,
        @NotNull @Min(Integer.MIN_VALUE) @Max(Integer.MAX_VALUE) Long patternSeed,
        @NotNull @Min(3) @Max(64) Integer dotDiameterPx,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.35") Double targetFillRatio,
        @NotNull @Min(0) @Max(64) Integer minimumDotSpacingPx,
        @NotNull @Min(0) @Max(1365) Integer borderPx,
        @NotNull Boolean fiducialsEnabled,
        @NotNull Boolean invertPattern
) {
    public BackgroundOrientedSchlierenParameters toParameters() {
        return new BackgroundOrientedSchlierenParameters(
                widthPx,
                heightPx,
                intendedDpi,
                patternSeed,
                dotDiameterPx,
                targetFillRatio,
                minimumDotSpacingPx,
                borderPx,
                fiducialsEnabled,
                invertPattern);
    }

    public static BackgroundOrientedSchlierenRequest defaults() {
        BackgroundOrientedSchlierenParameters defaults =
                BackgroundOrientedSchlierenParameters.defaults();
        return new BackgroundOrientedSchlierenRequest(
                defaults.widthPx(),
                defaults.heightPx(),
                defaults.intendedDpi(),
                defaults.patternSeed(),
                defaults.dotDiameterPx(),
                defaults.targetFillRatio(),
                defaults.minimumDotSpacingPx(),
                defaults.borderPx(),
                defaults.fiducialsEnabled(),
                defaults.invertPattern());
    }
}

package org.fresnel.backend.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.fresnel.measurement.BosTargetParameters;

/** Public request shape for the Background-Oriented Schlieren target plugin. */
public record BosTargetRequest(
        @NotNull @Min(BosTargetParameters.MIN_WIDTH_PX) @Max(BosTargetParameters.MAX_WIDTH_PX)
        Integer widthPx,
        @NotNull @Min(BosTargetParameters.MIN_HEIGHT_PX) @Max(BosTargetParameters.MAX_HEIGHT_PX)
        Integer heightPx,
        @NotNull @DecimalMin("50.0") @DecimalMax("2400.0")
        Double intendedDpi,
        @NotNull @Min(0) @Max(9007199254740991L)
        Long patternSeed,
        @NotNull @Min(BosTargetParameters.MIN_DOT_DIAMETER_PX)
        @Max(BosTargetParameters.MAX_DOT_DIAMETER_PX)
        Integer dotDiameterPx,
        @NotNull @DecimalMin("0.02") @DecimalMax("0.25")
        Double targetFillRatio,
        @NotNull @Min(BosTargetParameters.MIN_SPACING_PX)
        @Max(BosTargetParameters.MAX_SPACING_PX)
        Integer minimumDotSpacingPx,
        @NotNull @Min(BosTargetParameters.MIN_BORDER_PX) @Max(BosTargetParameters.MAX_BORDER_PX)
        Integer borderPx,
        @NotNull Boolean fiducialsEnabled,
        @NotNull Boolean invertPattern) {

    public BosTargetRequest normalized() {
        BosTargetParameters defaults = BosTargetParameters.defaults();
        return new BosTargetRequest(
                widthPx == null ? defaults.widthPx() : widthPx,
                heightPx == null ? defaults.heightPx() : heightPx,
                intendedDpi == null ? defaults.intendedDpi() : intendedDpi,
                patternSeed == null ? defaults.patternSeed() : patternSeed,
                dotDiameterPx == null ? defaults.dotDiameterPx() : dotDiameterPx,
                targetFillRatio == null ? defaults.targetFillRatio() : targetFillRatio,
                minimumDotSpacingPx == null
                        ? defaults.minimumDotSpacingPx()
                        : minimumDotSpacingPx,
                borderPx == null ? defaults.borderPx() : borderPx,
                fiducialsEnabled == null
                        ? defaults.fiducialsEnabled()
                        : fiducialsEnabled,
                invertPattern == null ? defaults.invertPattern() : invertPattern);
    }

    public BosTargetParameters toParameters() {
        BosTargetRequest value = normalized();
        return new BosTargetParameters(
                value.widthPx(),
                value.heightPx(),
                value.intendedDpi(),
                value.patternSeed(),
                value.dotDiameterPx(),
                value.targetFillRatio(),
                value.minimumDotSpacingPx(),
                value.borderPx(),
                value.fiducialsEnabled(),
                value.invertPattern());
    }
}

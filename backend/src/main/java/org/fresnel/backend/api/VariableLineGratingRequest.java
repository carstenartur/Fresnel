package org.fresnel.backend.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.fresnel.optics.AxisQuantity;
import org.fresnel.optics.GratingProgression;
import org.fresnel.optics.LineOrientation;
import org.fresnel.optics.Polarity;
import org.fresnel.optics.ProgressionDirection;
import org.fresnel.optics.VariableLineGratingParameters;

/** REST and job-file request body for a variable-line calibration grating. */
public record VariableLineGratingRequest(
        @NotNull @DecimalMin("10") @DecimalMax("210") Double widthMm,
        @NotNull @DecimalMin("10") @DecimalMax("297") Double heightMm,
        @NotNull LineOrientation lineOrientation,
        @NotNull @DecimalMin("5") @DecimalMax("10000") Double startPitchUm,
        @NotNull @DecimalMin("5") @DecimalMax("10000") Double endPitchUm,
        @NotNull GratingProgression progression,
        @NotNull ProgressionDirection progressionDirection,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1", inclusive = false) Double dutyCycle,
        @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double phaseOffsetCycles,
        @NotNull Polarity polarity,
        @NotNull @PositiveOrZero @DecimalMax("50") Double marginMm,
        @NotNull @Positive @DecimalMax("40") Double annotationSizeMm,
        @NotNull Boolean showAxis,
        @NotNull AxisQuantity axisQuantity,
        @NotNull @Min(2) @Max(21) Integer tickCount,
        @NotNull Boolean showReferenceBands,
        @NotNull @PositiveOrZero @DecimalMax("50") Double referenceBandSizeMm,
        @NotNull @DecimalMin("50") @DecimalMax("2400") Double dpi
) {
    public VariableLineGratingParameters toParameters() {
        return new VariableLineGratingParameters(
                widthMm,
                heightMm,
                lineOrientation,
                startPitchUm,
                endPitchUm,
                progression,
                progressionDirection,
                dutyCycle,
                phaseOffsetCycles,
                polarity,
                marginMm,
                annotationSizeMm,
                showAxis,
                axisQuantity,
                tickCount,
                showReferenceBands,
                referenceBandSizeMm,
                dpi);
    }

    public VariableLineGratingRequest normalized() {
        VariableLineGratingParameters defaults = VariableLineGratingParameters.defaults();
        return new VariableLineGratingRequest(
                widthMm == null ? defaults.widthMm() : widthMm,
                heightMm == null ? defaults.heightMm() : heightMm,
                lineOrientation == null ? defaults.lineOrientation() : lineOrientation,
                startPitchUm == null ? defaults.startPitchUm() : startPitchUm,
                endPitchUm == null ? defaults.endPitchUm() : endPitchUm,
                progression == null ? defaults.progression() : progression,
                progressionDirection == null ? defaults.progressionDirection() : progressionDirection,
                dutyCycle == null ? defaults.dutyCycle() : dutyCycle,
                phaseOffsetCycles == null ? defaults.phaseOffsetCycles() : phaseOffsetCycles,
                polarity == null ? defaults.polarity() : polarity,
                marginMm == null ? defaults.marginMm() : marginMm,
                annotationSizeMm == null ? defaults.annotationSizeMm() : annotationSizeMm,
                showAxis == null ? defaults.showAxis() : showAxis,
                axisQuantity == null ? defaults.axisQuantity() : axisQuantity,
                tickCount == null ? defaults.tickCount() : tickCount,
                showReferenceBands == null ? defaults.showReferenceBands() : showReferenceBands,
                referenceBandSizeMm == null ? defaults.referenceBandSizeMm() : referenceBandSizeMm,
                dpi == null ? defaults.dpi() : dpi);
    }
}

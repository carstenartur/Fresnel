package org.fresnel.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.MaskType;
import org.fresnel.optics.Polarity;
import org.fresnel.optics.WindowFoilParameters;

import java.util.ArrayList;
import java.util.List;

/** REST request body for a window-foil layout. */
public record WindowFoilRequest(
        @NotNull @Positive Double sheetWidthMm,
        @NotNull @Positive Double sheetHeightMm,
        @NotNull @Positive Double macroRadiusMm,
        @NotNull @Positive Double subDiameterMm,
        @NotNull @Positive Double subPitchMm,
        @NotNull @Positive Double wavelengthNm,
        @NotNull @Positive Double dpi,
        MaskType maskType,
        Polarity polarity,
        List<CellSpecDto> cellSpecs,
        Boolean drawCropMarks
) {

    public record CellSpecDto(
            @NotNull @Positive Double focalLengthMm,
            Double targetOffsetXmm,
            Double targetOffsetYmm
    ) {
        public WindowFoilParameters.CellSpec toCore() {
            return new WindowFoilParameters.CellSpec(
                    focalLengthMm,
                    targetOffsetXmm == null ? 0.0 : targetOffsetXmm,
                    targetOffsetYmm == null ? 0.0 : targetOffsetYmm);
        }
    }

    public WindowFoilParameters toParameters() {
        List<WindowFoilParameters.CellSpec> specs = new ArrayList<>();
        if (cellSpecs != null) {
            for (CellSpecDto d : cellSpecs) specs.add(d.toCore());
        }
        return new WindowFoilParameters(
                sheetWidthMm, sheetHeightMm,
                macroRadiusMm, subDiameterMm, subPitchMm,
                wavelengthNm, dpi,
                maskType == null ? MaskType.BINARY_AMPLITUDE : maskType,
                polarity == null ? Polarity.POSITIVE : polarity,
                specs,
                drawCropMarks != null && drawCropMarks);
    }
}

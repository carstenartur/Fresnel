package org.fresnel.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.CalibrationSheetGenerator;
import org.fresnel.optics.PdfExporter;

public record CalibrationSheetRequest(
        @NotNull @Positive Double dpi,
        @Positive Double printScale,
        @Positive Double wavelengthNm,
        @Positive Double focalLengthMm
) {
    public CalibrationSheetGenerator.CalibrationSheetParameters toParameters(PdfExporter.SheetSize sheetSize) {
        return new CalibrationSheetGenerator.CalibrationSheetParameters(
                dpi,
                sheetSize,
                printScale == null ? 1.0 : printScale,
                wavelengthNm,
                focalLengthMm);
    }
}

package org.fresnel.optics;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationSheetGeneratorTest {

    @Test
    void renderUsesRequestedSheetDimensions() {
        CalibrationSheetGenerator.CalibrationSheetParameters p =
                CalibrationSheetGenerator.CalibrationSheetParameters.of(600.0, PdfExporter.SheetSize.A4);
        RenderResult r = CalibrationSheetGenerator.render(p);
        assertTrue(Math.abs(r.widthMm() - PdfExporter.SheetSize.A4.widthMm) < 0.2);
        assertTrue(Math.abs(r.heightMm() - PdfExporter.SheetSize.A4.heightMm) < 0.2);
    }

    @Test
    void exportsContainCalibrationMetadata() throws Exception {
        CalibrationSheetGenerator.CalibrationSheetParameters p =
                new CalibrationSheetGenerator.CalibrationSheetParameters(
                        600.0, PdfExporter.SheetSize.A4, 1.0, 550.0, 1000.0);
        RenderResult r = CalibrationSheetGenerator.render(p);

        byte[] svg = SvgExporter.toSvgRasterBytes(r, p.dpi());
        String s = new String(svg, StandardCharsets.UTF_8);
        assertTrue(s.contains("DPI: 600.00"));

        byte[] pdf = PdfExporter.toPdfBytes(r, PdfExporter.SheetSize.A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 1);
            assertEquals(595.28f, doc.getPage(0).getMediaBox().getWidth(), 0.7f);
        }
    }
}

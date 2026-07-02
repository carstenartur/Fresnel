package org.fresnel.optics;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        byte[] svg = SvgExporter.toSvgRasterBytes(r, p.dpi(), CalibrationSheetGenerator.metadataText(p));
        String s = new String(svg, StandardCharsets.UTF_8);
        assertTrue(s.contains("DPI: 600.00"));
        assertTrue(s.contains("scale: 1.000"));
        assertTrue(s.contains("λ: 550.0 nm"));
        assertTrue(s.contains("f: 1000.0 mm"));

        byte[] pdf = PdfExporter.toPdfBytes(r, PdfExporter.SheetSize.A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 1);
            assertEquals(595.28f, doc.getPage(0).getMediaBox().getWidth(), 0.7f);
        }
    }

    @Test
    void rejectsNonFiniteOrNonPositiveOptionalInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CalibrationSheetGenerator.CalibrationSheetParameters(
                600.0, PdfExporter.SheetSize.A4, 1.0, Double.NaN, 1000.0));
        assertThrows(IllegalArgumentException.class, () -> new CalibrationSheetGenerator.CalibrationSheetParameters(
                600.0, PdfExporter.SheetSize.A4, 1.0, Double.POSITIVE_INFINITY, 1000.0));
        assertThrows(IllegalArgumentException.class, () -> new CalibrationSheetGenerator.CalibrationSheetParameters(
                600.0, PdfExporter.SheetSize.A4, 1.0, 550.0, 0.0));
    }

    @Test
    void printInstructionReflectsConfiguredScale() {
        var atDefaultScale = new CalibrationSheetGenerator.CalibrationSheetParameters(
                600.0, PdfExporter.SheetSize.A4, 1.0, null, null);
        assertEquals("Print at 100% / actual size. Disable fit-to-page.",
                CalibrationSheetGenerator.printInstructionText(atDefaultScale));

        var atNinetyPercent = new CalibrationSheetGenerator.CalibrationSheetParameters(
                600.0, PdfExporter.SheetSize.A4, 0.9, null, null);
        assertEquals("Print at 90.0% scale. Disable fit-to-page.",
                CalibrationSheetGenerator.printInstructionText(atNinetyPercent));
    }
}

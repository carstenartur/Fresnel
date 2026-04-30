package org.fresnel.optics;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PdfExporterTest {

    @Test
    void smallImageFitsOnSinglePage() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(20.0, 100.0, 550.0, 150.0);
        RenderResult r = ZonePlateRenderer.render(p);
        byte[] pdf = PdfExporter.toPdfBytes(r, PdfExporter.SheetSize.A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            float widthPt = doc.getPage(0).getMediaBox().getWidth();
            // A4 width ≈ 595.28 pt
            assertEquals(595.28f, widthPt, 0.5f);
        }
    }

    @Test
    void largeImageSplitsAcrossMultiplePages() throws Exception {
        // Sheet too big for A4: 250 mm × 250 mm > 210×297
        WindowFoilParameters wp = new WindowFoilParameters(
                250.0, 250.0, 30.0, 5.0, 5.0, 550.0, 80.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                java.util.List.of(WindowFoilParameters.CellSpec.onAxis(1500.0)),
                false);
        RenderResult r = WindowFoilRenderer.render(wp);
        byte[] pdf = PdfExporter.toPdfBytes(r, PdfExporter.SheetSize.A4);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 2,
                    "expected ≥ 2 pages, got " + doc.getNumberOfPages());
        }
    }

    @Test
    void fitSheetIsSinglePageSizedToImage() throws Exception {
        SingleZonePlateParameters p = SingleZonePlateParameters.onAxis(50.0, 200.0, 550.0, 100.0);
        RenderResult r = ZonePlateRenderer.render(p);
        byte[] pdf = PdfExporter.toPdfBytes(r, PdfExporter.SheetSize.FIT);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            float widthPt = doc.getPage(0).getMediaBox().getWidth();
            float expectedPt = (float) (r.widthMm() * 72.0 / 25.4);
            assertEquals(expectedPt, widthPt, 1.0f);
        }
    }
}

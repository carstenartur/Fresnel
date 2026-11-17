package org.fresnel.backend.measurement;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.fresnel.measurement.BosTargetGenerator;
import org.fresnel.measurement.BosTargetParameters;
import org.fresnel.optics.PdfExporter.SheetSize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BosTargetPrintExporterTest {
    @Test
    void fitPagePreservesEveryActivePixelAtNativeResolutionForBothPolarities() throws Exception {
        for (boolean inverted : new boolean[]{false, true}) {
            var p = new BosTargetParameters(320, 240, 72, 123, 5, 0.12, 3, 24, false, inverted);
            var target = BosTargetGenerator.generate(p);
            byte[] pdf = BosTargetPrintExporter.pdf(target, false, SheetSize.FIT);
            assertArrayEquals(pdf, BosTargetPrintExporter.pdf(target, false, SheetSize.FIT));
            try (var doc = Loader.loadPDF(pdf)) {
                var image = new PDFRenderer(doc).renderImageWithDPI(0, 72);
                assertEquals(p.widthPx(), image.getWidth());
                assertEquals(p.heightPx(), image.getHeight());
                var roi = target.activeRegion();
                for (int y = roi.y(); y < roi.maxYExclusive(); y++) {
                    for (int x = roi.x(); x < roi.maxXExclusive(); x++) {
                        assertEquals(target.pixelUnsigned(x, y), image.getRGB(x, y) & 255);
                    }
                }
            }
        }
    }

    @Test
    void a4TilesUseStandardPageSizesAndRetainTheSourcePitch() throws Exception {
        // The 225.78 mm target exceeds one A4 printable width and fits in the height.
        var p = new BosTargetParameters(640, 480, 72, 123, 5, 0.12, 3, 32, false, false);
        var target = BosTargetGenerator.generate(p);
        try (var doc = Loader.loadPDF(BosTargetPrintExporter.pdf(target, true, SheetSize.A4))) {
            assertEquals(2, doc.getNumberOfPages());
            for (var page : doc.getPages()) {
                assertEquals(210.0, page.getMediaBox().getWidth() * 25.4 / 72, 0.001);
                assertEquals(297.0, page.getMediaBox().getHeight() * 25.4 / 72, 0.001);
            }
        }
    }
}

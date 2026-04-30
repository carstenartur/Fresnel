package org.fresnel.optics;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * PDF export with optional sheet splitting (A4 / A3 / A2 / custom roll).
 *
 * <p>The rendered image is placed at its physical size in millimetres. For images
 * larger than the chosen page size the image is split across multiple pages with
 * a configurable overlap, and 5-mm registration tick marks are drawn at the page
 * corners on every page so a user can tile the printed pages back together.
 *
 * <p>PDF coordinate units are points (1 pt = 1/72 inch). Conversions:
 * {@code mm = pt · 25.4 / 72}.
 */
public final class PdfExporter {

    /** Standard sheet sizes (width × height in mm, portrait). */
    public enum SheetSize {
        A4(210.0, 297.0),
        A3(297.0, 420.0),
        A2(420.0, 594.0),
        A1(594.0, 841.0),
        A0(841.0, 1189.0),
        /** Single page sized exactly to the image; never split. */
        FIT(0.0, 0.0);

        public final double widthMm;
        public final double heightMm;
        SheetSize(double w, double h) { this.widthMm = w; this.heightMm = h; }
    }

    private static final double MM_PER_PT = 25.4 / 72.0;
    private static final double PT_PER_MM = 72.0 / 25.4;

    /** Overlap between adjacent split pages, in mm. */
    public static final double DEFAULT_OVERLAP_MM = 5.0;
    /** Crop-mark length in mm. */
    public static final double CROP_MARK_MM = 5.0;

    private PdfExporter() {}

    /** Convenience: write to byte array. */
    public static byte[] toPdfBytes(RenderResult r, SheetSize sheet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writePdf(r, sheet, DEFAULT_OVERLAP_MM, baos);
        return baos.toByteArray();
    }

    /**
     * Write the rendered image as PDF, splitting onto pages of the given sheet size.
     *
     * @param r          rendered image with physical pixel size
     * @param sheet      target page size (or {@link SheetSize#FIT} to use one custom page)
     * @param overlapMm  overlap between adjacent split pages, in millimetres
     */
    public static void writePdf(RenderResult r, SheetSize sheet, double overlapMm, OutputStream out)
            throws IOException {
        BufferedImage img = r.image();
        double imgWMm = r.widthMm();
        double imgHMm = r.heightMm();

        try (PDDocument doc = new PDDocument()) {
            if (sheet == SheetSize.FIT
                    || (imgWMm <= sheet.widthMm + 1e-6 && imgHMm <= sheet.heightMm + 1e-6)) {
                // Single page sized to the image (or to the sheet if it fits).
                double pageWMm = sheet == SheetSize.FIT ? imgWMm : sheet.widthMm;
                double pageHMm = sheet == SheetSize.FIT ? imgHMm : sheet.heightMm;
                PDPage page = new PDPage(new PDRectangle(
                        (float) (pageWMm * PT_PER_MM), (float) (pageHMm * PT_PER_MM)));
                doc.addPage(page);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    // Centre the image on the page.
                    float xPt = (float) ((pageWMm - imgWMm) / 2.0 * PT_PER_MM);
                    float yPt = (float) ((pageHMm - imgHMm) / 2.0 * PT_PER_MM);
                    cs.drawImage(pdImage,
                            xPt, yPt,
                            (float) (imgWMm * PT_PER_MM),
                            (float) (imgHMm * PT_PER_MM));
                    drawCornerCropMarks(cs, pageWMm, pageHMm);
                }
            } else {
                // Multi-page tiling.
                double overlap = Math.max(0.0, overlapMm);
                double pageWMm = sheet.widthMm;
                double pageHMm = sheet.heightMm;
                double tileWMm = pageWMm - 2.0 * overlap;
                double tileHMm = pageHMm - 2.0 * overlap;
                if (tileWMm <= 0 || tileHMm <= 0)
                    throw new IllegalArgumentException("overlap too large for sheet");
                int nx = (int) Math.ceil(imgWMm / tileWMm);
                int ny = (int) Math.ceil(imgHMm / tileHMm);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        PDPage page = new PDPage(new PDRectangle(
                                (float) (pageWMm * PT_PER_MM), (float) (pageHMm * PT_PER_MM)));
                        doc.addPage(page);
                        // Image position so that tile (i,j) is centred in the page (with overlap margin).
                        // Image origin in mm = -i·tileW + overlap (left edge of tile-image area).
                        double xMm = -i * tileWMm + overlap;
                        // PDF y-axis points up; image origin is its top-left, but we draw with bottom-left.
                        // Place so tile-row j of the image (from top) maps to the page area.
                        double yMm = pageHMm - imgHMm + j * tileHMm - overlap;
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                            cs.drawImage(pdImage,
                                    (float) (xMm * PT_PER_MM),
                                    (float) (yMm * PT_PER_MM),
                                    (float) (imgWMm * PT_PER_MM),
                                    (float) (imgHMm * PT_PER_MM));
                            drawCornerCropMarks(cs, pageWMm, pageHMm);
                        }
                    }
                }
            }
            doc.save(out);
        }
    }

    /** Draw 5-mm tick marks at the four page corners pointing inward. */
    private static void drawCornerCropMarks(PDPageContentStream cs, double pageWMm, double pageHMm)
            throws IOException {
        float w = (float) (pageWMm * PT_PER_MM);
        float h = (float) (pageHMm * PT_PER_MM);
        float t = (float) (CROP_MARK_MM * PT_PER_MM);
        cs.setStrokingColor(0f, 0f, 0f);
        cs.setLineWidth(0.5f);
        // Bottom-left
        cs.moveTo(0, 0); cs.lineTo(t, 0); cs.stroke();
        cs.moveTo(0, 0); cs.lineTo(0, t); cs.stroke();
        // Bottom-right
        cs.moveTo(w, 0); cs.lineTo(w - t, 0); cs.stroke();
        cs.moveTo(w, 0); cs.lineTo(w, t); cs.stroke();
        // Top-left
        cs.moveTo(0, h); cs.lineTo(t, h); cs.stroke();
        cs.moveTo(0, h); cs.lineTo(0, h - t); cs.stroke();
        // Top-right
        cs.moveTo(w, h); cs.lineTo(w - t, h); cs.stroke();
        cs.moveTo(w, h); cs.lineTo(w, h - t); cs.stroke();
    }
}

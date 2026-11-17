package org.fresnel.backend.measurement;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.fresnel.measurement.BosTarget;
import org.fresnel.optics.PdfExporter.SheetSize;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Keeps the source pixel pitch fixed in both print formats. Layout measurements are in mm. */
public final class BosTargetPrintExporter {
    private static final double STRIP_HEIGHT = 12;
    private static final int RULER_X = 3;
    private static final int RULER_LENGTH = 50;

    private BosTargetPrintExporter() {}

    private record Layout(double width, double height, double targetWidth, double targetHeight,
                          double pixelPitch, double idSize, boolean strip) {
        static Layout of(BosTarget target, boolean strip) {
            var p = target.parameters();
            double pitch = 25.4 / p.intendedDpi();
            double w = p.intendedWidthMm();
            double h = p.intendedHeightMm();
            // Reserve the central top border for the ID and keep both corner markers clear.
            double idSize = Math.min(3, Math.min(p.borderPx() * pitch / 2,
                    (w - 2 * p.borderPx() * pitch) / target.targetId().length()));
            return new Layout(strip ? Math.max(w, RULER_LENGTH + 2 * RULER_X) : w,
                    strip ? h + STRIP_HEIGHT : h, w, h, pitch, idSize, strip);
        }
    }

    public static byte[] svg(BosTarget target, boolean strip) {
        Layout l = Layout.of(target, strip);
        boolean inverted = target.parameters().invertPattern();
        String ink = inverted ? "white" : "black";
        int foreground = inverted ? 255 : 0;
        int width = target.parameters().widthPx();
        int height = target.parameters().heightPx();
        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(l.width)
                .append("mm\" height=\"").append(l.height).append("mm\" viewBox=\"0 0 ")
                .append(l.width).append(' ').append(l.height).append("\">\n");
        out.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        out.append("<g transform=\"scale(").append(l.pixelPitch).append(")\" shape-rendering=\"crispEdges\">\n");
        out.append("<rect width=\"").append(width).append("\" height=\"").append(height)
                .append("\" fill=\"").append(inverted ? "black" : "white").append("\"/>\n");
        out.append("<g fill=\"none\" stroke=\"").append(ink).append("\" stroke-width=\"1\">\n");
        // One horizontal stroke per contiguous run reproduces the exact source pixels,
        // including the asymmetric fiducials, without generating one XML node per pixel.
        for (int y = 0; y < height; y++) {
            StringBuilder row = new StringBuilder();
            int x = 0;
            while (x < width) {
                if (target.pixelUnsigned(x, y) != foreground) {
                    x++;
                    continue;
                }
                int start = x++;
                while (x < width && target.pixelUnsigned(x, y) == foreground) x++;
                row.append('M').append(start).append(' ').append(y).append(".5h").append(x - start);
            }
            if (!row.isEmpty()) out.append("<path d=\"").append(row).append("\"/>\n");
        }
        out.append("</g></g>\n");
        out.append("<text text-anchor=\"middle\" x=\"").append(l.targetWidth / 2)
                .append("\" y=\"").append(idBaseline(target, l)).append("\" font-family=\"sans-serif\" font-size=\"")
                .append(l.idSize).append("\" fill=\"").append(ink).append("\">").append(target.targetId()).append("</text>\n");
        if (strip) {
            double y = l.targetHeight + 4;
            out.append("<g stroke=\"black\" stroke-width=\"0.2\">\n");
            svgLine(out, RULER_X, y, RULER_X + RULER_LENGTH, y);
            for (int mm = 0; mm <= RULER_LENGTH; mm += 10) svgLine(out, RULER_X + mm, y - 1.5, RULER_X + mm, y + 1.5);
            out.append("</g><text x=\"").append(RULER_X).append("\" y=\"").append(l.targetHeight + 10)
                    .append("\" font-family=\"sans-serif\" font-size=\"2.6\">50 mm  ").append(target.targetId()).append("</text>\n");
        }
        return out.append("</svg>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void svgLine(StringBuilder out, double x1, double y1, double x2, double y2) {
        out.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
                .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2).append("\"/>\n");
    }

    public static byte[] pdf(BosTarget target, boolean strip, SheetSize sheet) throws IOException {
        Layout l = Layout.of(target, strip);
        boolean fitted = sheet == SheetSize.FIT;
        double w = fitted ? l.width : sheet.widthMm;
        double h = fitted ? l.height : sheet.heightMm;
        double margin = fitted ? 0 : 5;
        double usableWidth = w - 2 * margin;
        double usableHeight = h - 2 * margin;
        double overlap = fitted ? 0 : 5;
        int columns = tileCount(l.width, usableWidth, overlap);
        int rows = tileCount(l.height, usableHeight, overlap);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            doc.setDocumentId(Long.parseUnsignedLong(target.semanticSha256().substring(0, 16), 16));
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var image = LosslessFactory.createFromImage(doc, binaryImage(target));
            image.setInterpolate(false);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    var page = new PDPage(new PDRectangle(pt(w), pt(h)));
                    doc.addPage(page);
                    double left = columns == 1 ? (w - l.width) / 2 : margin - column * (usableWidth - overlap);
                    double top = rows == 1 ? (h - l.height) / 2 : margin - row * (usableHeight - overlap);
                    try (var cs = new PDPageContentStream(doc, page)) {
                        cs.addRect(pt(margin), pt(margin), pt(usableWidth), pt(usableHeight));
                        cs.clip();
                        cs.transform(Matrix.getTranslateInstance(pt(left), pt(h - top - l.height)));
                        drawPdf(cs, image, font, target, l);
                    }
                }
            }
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static int tileCount(double content, double available, double overlap) {
        return Math.max(1, (int) Math.ceil((content - overlap) / (available - overlap)));
    }

    private static BufferedImage binaryImage(BosTarget target) {
        int w = target.parameters().widthPx(), h = target.parameters().heightPx();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        byte[] packed = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int stride = (w + 7) / 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (target.pixelUnsigned(x, y) == 255) packed[y * stride + x / 8] |= (byte) (0x80 >>> (x % 8));
            }
        }
        return image;
    }

    private static void drawPdf(PDPageContentStream cs, PDImageXObject image, PDType1Font font,
                                BosTarget target, Layout l) throws IOException {
        cs.drawImage(image, 0, pt(l.height - l.targetHeight), pt(l.targetWidth), pt(l.targetHeight));
        cs.setNonStrokingColor(target.parameters().invertPattern() ? 1f : 0f);
        double idWidth = font.getStringWidth(target.targetId()) / 1000 * l.idSize;
        writeText(cs, font, target.targetId(), (l.targetWidth - idWidth) / 2,
                l.height - idBaseline(target, l), l.idSize);
        if (l.strip) {
            double y = STRIP_HEIGHT - 4;
            cs.setLineWidth(pt(0.2));
            cs.moveTo(pt(RULER_X), pt(y));
            cs.lineTo(pt(RULER_X + RULER_LENGTH), pt(y));
            for (int mm = 0; mm <= RULER_LENGTH; mm += 10) {
                cs.moveTo(pt(RULER_X + mm), pt(y - 1.5));
                cs.lineTo(pt(RULER_X + mm), pt(y + 1.5));
            }
            cs.stroke();
            cs.setNonStrokingColor(0f);
            writeText(cs, font, "50 mm  " + target.targetId(), RULER_X, STRIP_HEIGHT - 10, 2.6);
        }
    }

    private static double idBaseline(BosTarget target, Layout l) {
        return target.parameters().borderPx() * l.pixelPitch / 2 + l.idSize * 0.33;
    }

    private static void writeText(PDPageContentStream cs, PDType1Font font, String text,
                                  double x, double y, double size) throws IOException {
        cs.beginText();
        cs.setFont(font, pt(size));
        cs.newLineAtOffset(pt(x), pt(y));
        cs.showText(text);
        cs.endText();
    }

    private static float pt(double mm) {
        return (float) (mm * 72 / 25.4);
    }
}

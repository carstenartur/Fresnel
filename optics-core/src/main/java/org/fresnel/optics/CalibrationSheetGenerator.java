package org.fresnel.optics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Generates printable calibration sheets for verifying print scale, alignment and feature size.
 */
public final class CalibrationSheetGenerator {

    private static final double DEFAULT_SCALE = 1.0;

    private CalibrationSheetGenerator() {}

    public record CalibrationSheetParameters(
            double dpi,
            PdfExporter.SheetSize sheetSize,
            double printScale,
            Double wavelengthNm,
            Double focalLengthMm
    ) {
        public CalibrationSheetParameters {
            if (!Double.isFinite(dpi) || dpi <= 0.0) throw new IllegalArgumentException("dpi must be > 0");
            if (sheetSize == null || sheetSize == PdfExporter.SheetSize.FIT) {
                sheetSize = PdfExporter.SheetSize.A4;
            }
            if (!Double.isFinite(printScale) || printScale <= 0.0) {
                throw new IllegalArgumentException("printScale must be > 0");
            }
        }

        public static CalibrationSheetParameters of(double dpi, PdfExporter.SheetSize sheetSize) {
            return new CalibrationSheetParameters(dpi, sheetSize, DEFAULT_SCALE, null, null);
        }
    }

    public static RenderResult render(CalibrationSheetParameters p) {
        double pixelMm = 25.4 / p.dpi();
        int widthPx = (int) Math.max(1, Math.round(p.sheetSize.widthMm / pixelMm));
        int heightPx = (int) Math.max(1, Math.round(p.sheetSize.heightMm / pixelMm));
        BufferedImage img = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, widthPx, heightPx);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, mmToPx(2.2, p.dpi()))));

            drawFrame(g, p, widthPx, heightPx);
            drawCenterMark(g, p, widthPx, heightPx);
            drawScaleBars(g, p);
            drawDpiFields(g, p);
            drawLineSpaceTargets(g, p);
            drawDensityPatches(g, p);
            drawCircularApertures(g, p, widthPx);
            drawMetadata(g, p, widthPx, heightPx);
        } finally {
            g.dispose();
        }
        return new RenderResult(img, pixelMm);
    }

    private static void drawFrame(Graphics2D g, CalibrationSheetParameters p, int widthPx, int heightPx) {
        int margin = mmToPx(5, p.dpi());
        g.drawRect(margin, margin, widthPx - 2 * margin, heightPx - 2 * margin);
        int reg = mmToPx(6, p.dpi());
        g.fillRect(margin - reg / 2, margin - reg / 2, reg, reg);
        g.fillRect(widthPx - margin - reg / 2, margin - reg / 2, reg, reg);
        g.fillRect(margin - reg / 2, heightPx - margin - reg / 2, reg, reg);
        g.fillRect(widthPx - margin - reg / 2, heightPx - margin - reg / 2, reg, reg);
    }

    private static void drawCenterMark(Graphics2D g, CalibrationSheetParameters p, int widthPx, int heightPx) {
        int cx = widthPx / 2;
        int cy = heightPx / 2;
        int arm = mmToPx(12, p.dpi());
        int gap = mmToPx(2, p.dpi());
        g.drawLine(cx - arm, cy, cx - gap, cy);
        g.drawLine(cx + gap, cy, cx + arm, cy);
        g.drawLine(cx, cy - arm, cx, cy - gap);
        g.drawLine(cx, cy + gap, cx, cy + arm);
        int r = mmToPx(1.5, p.dpi());
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
    }

    private static void drawScaleBars(Graphics2D g, CalibrationSheetParameters p) {
        int x0 = mmToPx(15, p.dpi());
        int y = mmToPx(28, p.dpi());
        int width = mmToPx(100, p.dpi());
        g.drawLine(x0, y, x0 + width, y);
        for (int mm = 0; mm <= 100; mm += 10) {
            int x = x0 + mmToPx(mm, p.dpi());
            int tick = mm % 50 == 0 ? mmToPx(4, p.dpi()) : mmToPx(2.2, p.dpi());
            g.drawLine(x, y - tick, x, y + tick);
        }
        g.drawString("Scale bar 0-100 mm", x0, y - mmToPx(3, p.dpi()));
    }

    private static void drawDpiFields(Graphics2D g, CalibrationSheetParameters p) {
        int x = mmToPx(15, p.dpi());
        int y = mmToPx(40, p.dpi());
        int w = mmToPx(22, p.dpi());
        int h = mmToPx(12, p.dpi());
        g.drawString("DPI / pixel pitch targets", x, y - mmToPx(2, p.dpi()));
        drawStripePatch(g, x, y, w, h, 1);
        drawStripePatch(g, x + w + mmToPx(3, p.dpi()), y, w, h, 2);
        drawStripePatch(g, x + 2 * (w + mmToPx(3, p.dpi())), y, w, h, 3);
        g.drawString("1px", x, y + h + mmToPx(4, p.dpi()));
        g.drawString("2px", x + w + mmToPx(3, p.dpi()), y + h + mmToPx(4, p.dpi()));
        g.drawString("3px", x + 2 * (w + mmToPx(3, p.dpi())), y + h + mmToPx(4, p.dpi()));
    }

    private static void drawLineSpaceTargets(Graphics2D g, CalibrationSheetParameters p) {
        int x = mmToPx(15, p.dpi());
        int y = mmToPx(66, p.dpi());
        int w = mmToPx(15, p.dpi());
        int h = mmToPx(18, p.dpi());
        g.drawString("Line-space targets", x, y - mmToPx(2, p.dpi()));
        for (int i = 0; i < 4; i++) {
            int lp = i + 1;
            int px = x + i * (w + mmToPx(3, p.dpi()));
            drawStripePatch(g, px, y, w, h, lp);
            g.drawString(lp + "px", px, y + h + mmToPx(4, p.dpi()));
        }
    }

    private static void drawDensityPatches(Graphics2D g, CalibrationSheetParameters p) {
        int x = mmToPx(15, p.dpi());
        int y = mmToPx(95, p.dpi());
        int s = mmToPx(12, p.dpi());
        g.drawString("Density patches", x, y - mmToPx(2, p.dpi()));
        g.setColor(Color.BLACK);
        g.fillRect(x, y, s, s);
        g.setColor(Color.WHITE);
        g.fillRect(x + s + mmToPx(3, p.dpi()), y, s, s);
        g.setColor(Color.BLACK);
        g.drawRect(x + s + mmToPx(3, p.dpi()), y, s, s);
    }

    private static void drawCircularApertures(Graphics2D g, CalibrationSheetParameters p, int widthPx) {
        int cx = widthPx - mmToPx(45, p.dpi());
        int y = mmToPx(40, p.dpi());
        g.drawString("Aperture refs", cx - mmToPx(8, p.dpi()), y - mmToPx(3, p.dpi()));
        int[] diametersMm = {5, 10, 20};
        for (int i = 0; i < diametersMm.length; i++) {
            int d = mmToPx(diametersMm[i], p.dpi());
            int oy = y + i * mmToPx(24, p.dpi());
            g.drawOval(cx - d / 2, oy, d, d);
            g.drawString(diametersMm[i] + " mm", cx + mmToPx(14, p.dpi()), oy + d / 2);
        }
    }

    private static void drawMetadata(Graphics2D g, CalibrationSheetParameters p, int widthPx, int heightPx) {
        int x = mmToPx(15, p.dpi());
        int y = heightPx - mmToPx(16, p.dpi());
        StringBuilder meta = new StringBuilder()
                .append(String.format(Locale.ROOT, "Fresnel calibration | DPI=%.1f | scale=%.3f", p.dpi(), p.printScale()));
        if (p.wavelengthNm != null) meta.append(String.format(Locale.ROOT, " | λ=%.1f nm", p.wavelengthNm));
        if (p.focalLengthMm != null) meta.append(String.format(Locale.ROOT, " | f=%.1f mm", p.focalLengthMm));
        g.drawString(meta.toString(), x, y);
        g.drawString("Print at 100% / actual size. Disable fit-to-page.", x, y + mmToPx(4, p.dpi()));
        g.drawString("Common errors: shrink/expand, blur/bleed, anisotropic scaling, offset registration.",
                x, y + mmToPx(8, p.dpi()));
        g.drawString("https://github.com/carstenartur/Fresnel", widthPx - mmToPx(70, p.dpi()), heightPx - mmToPx(5, p.dpi()));
    }

    private static void drawStripePatch(Graphics2D g, int x, int y, int w, int h, int linePx) {
        g.drawRect(x, y, w, h);
        int period = Math.max(1, linePx * 2);
        for (int cx = x; cx < x + w; cx += period) {
            g.fillRect(cx, y + 1, Math.min(linePx, x + w - cx), Math.max(1, h - 1));
        }
    }

    private static int mmToPx(double mm, double dpi) {
        return (int) Math.max(1, Math.round(mm * dpi / 25.4));
    }
}

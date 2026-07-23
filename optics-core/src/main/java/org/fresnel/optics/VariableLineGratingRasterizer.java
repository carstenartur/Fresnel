package org.fresnel.optics;

import java.util.Locale;

/** Dot-addressed binary rasterizer used by PNG preview and native PCL output. */
public final class VariableLineGratingRasterizer {

    private VariableLineGratingRasterizer() {}

    public static MonochromeRaster rasterize(
            VariableLineGratingParameters p,
            double dpiPageX,
            double dpiPageY) {
        if (!Double.isFinite(dpiPageX) || dpiPageX <= 0.0
                || !Double.isFinite(dpiPageY) || dpiPageY <= 0.0) {
            throw new IllegalArgumentException("page-axis DPI must be finite and positive");
        }
        int width = Math.max(1, (int) Math.round(p.widthMm() * dpiPageX / Units.INCH_MM));
        int height = Math.max(1, (int) Math.round(p.heightMm() * dpiPageY / Units.INCH_MM));
        long pixels = (long) width * height;
        if (pixels > VariableLineGratingParameters.MAX_RASTER_PIXELS) {
            throw new IllegalArgumentException(
                    "requested printer raster exceeds the safe "
                            + VariableLineGratingParameters.MAX_RASTER_PIXELS + " pixel limit");
        }
        int rowBytes = (width + 7) / 8;
        byte[] data = new byte[rowBytes * height];
        MutableTarget target = new MutableTarget(width, height, rowBytes, data);

        double mmPerDotX = Units.INCH_MM / dpiPageX;
        double mmPerDotY = Units.INCH_MM / dpiPageY;
        for (int y = 0; y < height; y++) {
            double yMm = (y + 0.5) * mmPerDotY;
            for (int x = 0; x < width; x++) {
                double xMm = (x + 0.5) * mmPerDotX;
                if (VariableLineGratingModel.isOpaque(p, xMm, yMm)) target.setBlack(x, y);
            }
        }
        drawAnnotations(target, p, dpiPageX, dpiPageY);
        return new MonochromeRaster(width, height, rowBytes, data, dpiPageX, dpiPageY);
    }

    private static void drawAnnotations(
            MutableTarget target,
            VariableLineGratingParameters p,
            double dpiPageX,
            double dpiPageY) {
        if (!p.showAxis()) return;
        VariableLineGratingModel.Layout layout = VariableLineGratingModel.layout(p);
        int scale = Math.max(1, Math.min(4,
                (int) Math.round(p.annotationSizeMm()
                        * (p.lineOrientation() == LineOrientation.VERTICAL ? dpiPageY : dpiPageX)
                        / Units.INCH_MM / 22.0)));
        if (p.lineOrientation() == LineOrientation.VERTICAL) {
            int axisY = dotY(layout.axisCoordinateMm(), dpiPageY);
            int x0 = dotX(layout.activeXmm(), dpiPageX);
            int x1 = dotX(layout.activeXmm() + layout.activeWidthMm(), dpiPageX);
            target.horizontal(x0, x1, axisY);
            for (int i = 0; i < p.tickCount(); i++) {
                double u = i / (double) (p.tickCount() - 1);
                int x = dotX(layout.activeXmm() + u * layout.activeWidthMm(), dpiPageX);
                target.vertical(x, axisY - 4 * scale, axisY + 4 * scale);
                String label = axisLabel(p, u, dpiPageX);
                int labelX = x - RasterText5x7.textWidth(label, scale) / 2;
                RasterText5x7.draw(target, labelX, axisY + 6 * scale, label, scale);
            }
            String title = "VERTICAL LINES PAGE-X PRINT-100%";
            int titleX = Math.max(0, (target.width - RasterText5x7.textWidth(title, scale)) / 2);
            int titleY = Math.min(target.height - 7 * scale - 1,
                    dotY(p.heightMm() - p.marginMm() * 0.55, dpiPageY));
            RasterText5x7.draw(target, titleX, Math.max(axisY + 15 * scale, titleY), title, scale);
        } else {
            int axisX = dotX(layout.axisCoordinateMm(), dpiPageX);
            int y0 = dotY(layout.activeYmm(), dpiPageY);
            int y1 = dotY(layout.activeYmm() + layout.activeHeightMm(), dpiPageY);
            target.vertical(axisX, y0, y1);
            for (int i = 0; i < p.tickCount(); i++) {
                double u = i / (double) (p.tickCount() - 1);
                int y = dotY(layout.activeYmm() + u * layout.activeHeightMm(), dpiPageY);
                target.horizontal(axisX - 4 * scale, axisX + 4 * scale, y);
                String label = axisLabel(p, u, dpiPageY);
                RasterText5x7.draw(target, axisX + 6 * scale, y - 3 * scale, label, scale);
            }
            String title = "HORIZONTAL LINES PAGE-Y PRINT-100%";
            int titleX = Math.min(target.width - 8 * scale - 1,
                    dotX(p.widthMm() - p.marginMm() * 0.6, dpiPageX));
            int titleY = Math.max(0,
                    (target.height - RasterText5x7.textWidth(title, scale)) / 2);
            RasterText5x7.drawClockwise(target, titleX, titleY, title, scale);
        }
    }

    static String axisLabel(VariableLineGratingParameters p, double u, double selectedDpi) {
        double pitchMm = VariableLineGratingModel.pitchMmAtNormalized(p, u);
        return switch (p.axisQuantity()) {
            case PITCH_UM -> compact(pitchMm * 1000.0) + "UM";
            case LINES_PER_MM -> compact(1.0 / pitchMm) + "L/MM";
            case DEVICE_DOTS_PER_PERIOD -> compact(pitchMm * selectedDpi / Units.INCH_MM) + "DOT";
        };
    }

    private static String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 100.0 || Math.abs(value - Math.rint(value)) < 0.05) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static int dotX(double mm, double dpi) {
        return (int) Math.round(mm * dpi / Units.INCH_MM);
    }

    private static int dotY(double mm, double dpi) {
        return (int) Math.round(mm * dpi / Units.INCH_MM);
    }

    private static final class MutableTarget implements RasterText5x7.Target {
        private final int width;
        private final int height;
        private final int rowBytes;
        private final byte[] data;

        private MutableTarget(int width, int height, int rowBytes, byte[] data) {
            this.width = width;
            this.height = height;
            this.rowBytes = rowBytes;
            this.data = data;
        }

        @Override
        public void setBlack(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return;
            int offset = y * rowBytes + (x >>> 3);
            data[offset] |= (byte) (0x80 >>> (x & 7));
        }

        private void horizontal(int x0, int x1, int y) {
            int from = Math.min(x0, x1);
            int to = Math.max(x0, x1);
            for (int x = from; x <= to; x++) setBlack(x, y);
        }

        private void vertical(int x, int y0, int y1) {
            int from = Math.min(y0, y1);
            int to = Math.max(y0, y1);
            for (int y = from; y <= to; y++) setBlack(x, y);
        }
    }
}

package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a window-foil layout: a rectangular sheet of W × H tiled with hexagonal
 * macro cells on a flat-top hex grid (gap-less). Each cell uses
 * {@link HexMacroCellRenderer} semantics with its own focal length and target offset.
 *
 * <p>For very large sheets the resulting image can be huge (e.g. 1 m² @ 600 dpi
 * ≈ 23k × 23k px). Callers that drive printable PDFs are expected to render at the
 * target DPI and then split into pages — see {@code PdfExporter}.
 */
public final class WindowFoilRenderer {

    private WindowFoilRenderer() {}

    public static RenderResult render(WindowFoilParameters p) {
        double pixelSizeMm = Units.pixelSizeMm(p.dpi());
        int wPx = Math.max(2, (int) Math.ceil(p.sheetWidthMm() / pixelSizeMm));
        int hPx = Math.max(2, (int) Math.ceil(p.sheetHeightMm() / pixelSizeMm));

        BufferedImage img = new BufferedImage(wPx, hPx, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = img.getRaster();

        List<double[]> cellCentres = cellCentresInsideSheet(
                p.sheetWidthMm(), p.sheetHeightMm(), p.macroRadiusMm());
        double R = p.macroRadiusMm();
        double sqrt3 = Math.sqrt(3.0);
        double halfFlat = R * sqrt3 / 2.0;
        double invSqrt3 = 1.0 / sqrt3;

        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double k = 2.0 * Math.PI / lambdaMm;
        double subRadiusMm = p.subDiameterMm() / 2.0;
        double subRadiusMmSq = subRadiusMm * subRadiusMm;
        double subPitchMm = p.subPitchMm();
        boolean binary = p.maskType() == MaskType.BINARY_AMPLITUDE;
        boolean positive = p.polarity() == Polarity.POSITIVE;

        // Sub-element centres within one cell are derived analytically per pixel via
        // HexMacroCellRenderer.nearestLatticeContaining (O(1) lattice inversion),
        // avoiding the previous O(numSubCentres) scan per pixel.

        // Render row by row.
        int[] rowBuf = new int[wPx];
        for (int yPx = 0; yPx < hPx; yPx++) {
            // Sheet coords (mm): origin at top-left, +x right, +y down.
            double yMm = (yPx + 0.5) * pixelSizeMm;
            for (int xPx = 0; xPx < wPx; xPx++) rowBuf[xPx] = 0;

            // For each cell whose vertical extent intersects this row, render its slice.
            for (int cellIdx = 0; cellIdx < cellCentres.size(); cellIdx++) {
                double[] c = cellCentres.get(cellIdx);
                double cy = c[1];
                if (Math.abs(yMm - cy) > halfFlat) continue;
                double cx = c[0];
                WindowFoilParameters.CellSpec spec = p.specForCell(cellIdx);
                double zfMm = spec.focalLengthMm();
                double zfSqMm = zfMm * zfMm;

                // Determine x range covered by this cell at this row.
                double dyLocal = yMm - cy;
                // Hex (flat-top) constraint at given y: |x_local| ≤ R - |y_local|/√3
                double xHalfMm = R - Math.abs(dyLocal) * invSqrt3;
                if (xHalfMm <= 0) continue;
                int xMinPx = Math.max(0, (int) Math.floor((cx - xHalfMm) / pixelSizeMm));
                int xMaxPx = Math.min(wPx - 1, (int) Math.ceil((cx + xHalfMm) / pixelSizeMm));

                for (int xPx = xMinPx; xPx <= xMaxPx; xPx++) {
                    double xMm = (xPx + 0.5) * pixelSizeMm;
                    double dxLocal = xMm - cx;
                    if (Math.abs(dxLocal) + Math.abs(dyLocal) * invSqrt3 > R) continue;

                    // Find sub-element containing (dxLocal, dyLocal) in O(1) via the
                    // analytical lattice inverse instead of scanning the centre list.
                    double[] sub = HexMacroCellRenderer.nearestLatticeContaining(
                            dxLocal, dyLocal, subPitchMm, subRadiusMmSq, R);
                    if (sub == null) continue;
                    double sx = sub[0];
                    double sy = sub[1];
                    double xfLocal = spec.targetOffsetXmm() - sx;
                    double yfLocal = spec.targetOffsetYmm() - sy;
                    double dxF = (dxLocal - sx) - xfLocal;
                    double dyF = (dyLocal - sy) - yfLocal;
                    double L = Math.sqrt(dxF * dxF + dyF * dyF + zfSqMm);
                    double phi = k * L;
                    int v;
                    if (binary) {
                        double cv = Math.cos(phi);
                        boolean transparent = positive ? (cv >= 0.0) : (cv < 0.0);
                        v = transparent ? 255 : 0;
                    } else {
                        double wrapped = phi - 2.0 * Math.PI * Math.floor(phi / (2.0 * Math.PI));
                        v = (int) Math.min(255, Math.max(0, Math.round(wrapped * (255.0 / (2.0 * Math.PI)))));
                        if (!positive) v = 255 - v;
                    }
                    rowBuf[xPx] = v;
                }
            }
            raster.setSamples(0, yPx, wPx, 1, 0, rowBuf);
        }

        if (p.drawCropMarks()) {
            drawCropMarks(img, p, cellCentres, pixelSizeMm);
        }
        return new RenderResult(img, pixelSizeMm);
    }

    /**
     * Compute hex-cell centres on a flat-top tiling whose centres lie inside
     * the rectangular sheet. Pitch in x = √3·R; pitch in y = 1.5·R, with
     * alternating-row x-offset of √3·R/2 (gap-less hex tiling).
     */
    static List<double[]> cellCentresInsideSheet(double wMm, double hMm, double R) {
        List<double[]> out = new ArrayList<>();
        double sqrt3 = Math.sqrt(3.0);
        double pitchX = sqrt3 * R;
        double pitchY = 1.5 * R;
        int nRows = (int) Math.ceil(hMm / pitchY) + 2;
        int nCols = (int) Math.ceil(wMm / pitchX) + 2;
        for (int j = 0; j <= nRows; j++) {
            double cy = j * pitchY + R;          // first row centre offset by R from top
            if (cy > hMm) break;
            double xOff = (j & 1) == 0 ? pitchX / 2.0 : pitchX;
            for (int i = 0; i <= nCols; i++) {
                double cx = i * pitchX + xOff - pitchX / 2.0;
                if (cx > wMm) break;
                if (cx < 0 || cy < 0) continue;
                out.add(new double[]{cx, cy});
            }
        }
        return out;
    }

    /** Draw 5 mm tick marks at the four sheet corners and small marks at each cell top. */
    private static void drawCropMarks(BufferedImage img, WindowFoilParameters p,
                                      List<double[]> cellCentres, double pixelMm) {
        WritableRaster raster = img.getRaster();
        int w = img.getWidth();
        int h = img.getHeight();
        int tickPx = Math.max(4, (int) Math.round(5.0 / pixelMm));
        // Sheet corners: short ticks pointing inward
        drawHLine(raster, 0, 0, tickPx);
        drawVLine(raster, 0, 0, tickPx);
        drawHLine(raster, w - tickPx, 0, tickPx);
        drawVLine(raster, w - 1, 0, tickPx);
        drawHLine(raster, 0, h - 1, tickPx);
        drawVLine(raster, 0, h - tickPx, tickPx);
        drawHLine(raster, w - tickPx, h - 1, tickPx);
        drawVLine(raster, w - 1, h - tickPx, tickPx);
        // Small cross at each cell centre (1 mm long).
        int crossPx = Math.max(2, (int) Math.round(1.0 / pixelMm));
        for (double[] c : cellCentres) {
            int cx = (int) Math.round(c[0] / pixelMm);
            int cy = (int) Math.round(c[1] / pixelMm);
            drawHLine(raster, Math.max(0, cx - crossPx), cy, Math.min(w - cx + crossPx, 2 * crossPx + 1));
            drawVLine(raster, cx, Math.max(0, cy - crossPx), Math.min(h - cy + crossPx, 2 * crossPx + 1));
        }
    }

    private static void drawHLine(WritableRaster r, int x, int y, int len) {
        if (y < 0 || y >= r.getHeight()) return;
        for (int i = 0; i < len; i++) {
            int xx = x + i;
            if (xx < 0 || xx >= r.getWidth()) continue;
            r.setSample(xx, y, 0, 255);
        }
    }

    private static void drawVLine(WritableRaster r, int x, int y, int len) {
        if (x < 0 || x >= r.getWidth()) return;
        for (int i = 0; i < len; i++) {
            int yy = y + i;
            if (yy < 0 || yy >= r.getHeight()) continue;
            r.setSample(x, yy, 0, 255);
        }
    }

    /** Number of cells that would be tiled across the sheet. */
    public static int countCells(WindowFoilParameters p) {
        return cellCentresInsideSheet(p.sheetWidthMm(), p.sheetHeightMm(), p.macroRadiusMm()).size();
    }
}

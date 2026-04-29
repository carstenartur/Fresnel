package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a hexagonal macro cell composed of many sub-zone-plates that all converge
 * on a common image plane.
 *
 * <p>Sub-elements are placed on a triangular (hex) lattice with pitch {@code subPitchMm}.
 * Sub-element centres outside the outer hexagonal aperture are skipped. Inside the cell,
 * each pixel is rendered using the local zone-plate phase formula of the nearest
 * sub-element whose own circular support contains the pixel; pixels outside every
 * sub-element circle and inside the hex are rendered as background (black).
 */
public final class HexMacroCellRenderer {

    private HexMacroCellRenderer() {}

    public static RenderResult render(HexMacroCellParameters p) {
        double pixelSizeMm = Units.pixelSizeMm(p.dpi());
        // Macro cell flat-to-flat width = √3 · radius. Hex width (vertex-to-vertex) = 2·radius.
        double extentMm = 2.0 * p.macroRadiusMm();
        int sizePx = Math.max(2, (int) Math.ceil(extentMm / pixelSizeMm));
        if (sizePx % 2 == 0) sizePx++;

        double centerPx = (sizePx - 1) / 2.0;
        double subRadiusMm = p.subDiameterMm() / 2.0;
        double subRadiusMmSq = subRadiusMm * subRadiusMm;

        List<double[]> centres = hexLatticeCentresInsideHex(p.macroRadiusMm(), p.subPitchMm());

        double lambdaMm = Units.nmToMm(p.wavelengthNm());
        double k = 2.0 * Math.PI / lambdaMm;
        double zfMm = p.focalLengthMm();
        double zfSqMm = zfMm * zfMm;
        boolean binary = p.maskType() == MaskType.BINARY_AMPLITUDE;
        boolean positive = p.polarity() == Polarity.POSITIVE;

        BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = img.getRaster();
        int[] row = new int[sizePx];

        // Hex aperture parameters: flat-top hex inscribed/circumscribed of radius R.
        // Point (x,y) lies inside iff |y| ≤ R·√3/2 AND |x|+|y|/√3 ≤ R.
        double R = p.macroRadiusMm();
        double sqrt3 = Math.sqrt(3.0);
        double halfFlat = R * sqrt3 / 2.0;
        double invSqrt3 = 1.0 / sqrt3;

        for (int yPx = 0; yPx < sizePx; yPx++) {
            double yMm = (yPx - centerPx) * pixelSizeMm;
            double absY = Math.abs(yMm);
            for (int xPx = 0; xPx < sizePx; xPx++) {
                double xMm = (xPx - centerPx) * pixelSizeMm;
                row[xPx] = 0;
                // Hex aperture clip
                if (absY > halfFlat) continue;
                if (Math.abs(xMm) + absY * invSqrt3 > R) continue;

                // Find a sub-element that contains this pixel.
                // The lattice pitch ≥ sub-diameter, so pixel can belong to at most one sub-circle.
                double[] cMatch = nearestContainingCentre(xMm, yMm, centres, subRadiusMmSq);
                if (cMatch == null) continue;

                double cx = cMatch[0];
                double cy = cMatch[1];
                // Each sub-plate focuses on common target → its local off-axis target is:
                double xfLocal = p.targetOffsetXmm() - cx;
                double yfLocal = p.targetOffsetYmm() - cy;
                double dxF = (xMm - cx) - xfLocal;
                double dyF = (yMm - cy) - yfLocal;
                double L = Math.sqrt(dxF * dxF + dyF * dyF + zfSqMm);
                double phi = k * L;
                int v;
                if (binary) {
                    double c = Math.cos(phi);
                    boolean transparent = positive ? (c >= 0.0) : (c < 0.0);
                    v = transparent ? 255 : 0;
                } else {
                    double wrapped = phi - 2.0 * Math.PI * Math.floor(phi / (2.0 * Math.PI));
                    v = (int) Math.min(255, Math.max(0, Math.round(wrapped * (255.0 / (2.0 * Math.PI)))));
                    if (!positive) v = 255 - v;
                }
                row[xPx] = v;
            }
            raster.setSamples(0, yPx, sizePx, 1, 0, row);
        }
        return new RenderResult(img, pixelSizeMm);
    }

    /**
     * Generate hex-lattice centres inside the flat-top outer hex of circumscribed radius R.
     * The lattice pitch is {@code pitch}; rows are spaced by {@code pitch · √3 / 2}.
     */
    static List<double[]> hexLatticeCentresInsideHex(double R, double pitch) {
        List<double[]> out = new ArrayList<>();
        double sqrt3 = Math.sqrt(3.0);
        double rowDy = pitch * sqrt3 / 2.0;
        int nRows = (int) Math.ceil(R / rowDy) + 1;
        double halfFlat = R * sqrt3 / 2.0;
        double invSqrt3 = 1.0 / sqrt3;
        for (int j = -nRows; j <= nRows; j++) {
            double cy = j * rowDy;
            if (Math.abs(cy) > halfFlat + 1e-9) continue;
            double xOffset = (j & 1) == 0 ? 0.0 : pitch / 2.0;
            int nCols = (int) Math.ceil(R / pitch) + 1;
            for (int i = -nCols; i <= nCols; i++) {
                double cx = i * pitch + xOffset;
                if (Math.abs(cx) + Math.abs(cy) * invSqrt3 > R + 1e-9) continue;
                out.add(new double[]{cx, cy});
            }
        }
        return out;
    }

    /** First centre whose circular support of radius² rSq contains (x,y), or null. */
    private static double[] nearestContainingCentre(double x, double y, List<double[]> centres, double rSq) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] c : centres) {
            double dx = x - c[0];
            double dy = y - c[1];
            double d = dx * dx + dy * dy;
            if (d <= rSq && d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Number of sub-elements that fit inside the outer hex for the given parameters. */
    public static int countSubElements(HexMacroCellParameters p) {
        return hexLatticeCentresInsideHex(p.macroRadiusMm(), p.subPitchMm()).size();
    }
}

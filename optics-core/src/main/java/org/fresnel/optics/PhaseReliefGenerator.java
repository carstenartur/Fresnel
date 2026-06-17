package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Converts greyscale phase masks to physical height maps.
 */
public final class PhaseReliefGenerator {

    private PhaseReliefGenerator() {}

    /**
     * Convert a greyscale phase mask to a height map in millimetres.
     *
     * <p>Pixel value 0 maps to 0 rad and pixel value 255 maps to
     * {@code params.maxPhaseShiftRad()}.
     */
    public static double[][] toHeightMapMm(BufferedImage phaseMask, ReliefParameters params) {
        if (phaseMask == null) throw new IllegalArgumentException("phaseMask required");
        int w = phaseMask.getWidth();
        int h = phaseMask.getHeight();
        if (w < 2 || h < 2) throw new IllegalArgumentException("phaseMask must be at least 2x2");

        double lambdaMm = Units.nmToMm(params.wavelengthNm());
        double heightPerGray = (params.maxPhaseShiftRad() * lambdaMm)
                / (255.0 * 2.0 * Math.PI * params.refractiveIndexDelta());

        WritableRaster r = phaseMask.getRaster();
        int[] row = new int[w];
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
                out[y][x] = row[x] * heightPerGray;
            }
        }
        return out;
    }
}

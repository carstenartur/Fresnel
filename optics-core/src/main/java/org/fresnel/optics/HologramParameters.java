package org.fresnel.optics;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Parameters for the Gerchberg–Saxton hologram synthesis.
 *
 * @param targetImage         square greyscale target image (must be power-of-two side, ≤ 1024)
 * @param iterations          number of GS iterations (typical 30–100)
 * @param outputType          {@link OutputType#BINARY_PHASE} (0/π) or {@link OutputType#GREYSCALE_PHASE}
 * @param dpi                 printer resolution for export (the hologram pixel pitch derives from this)
 */
public record HologramParameters(
        BufferedImage targetImage,
        int iterations,
        OutputType outputType,
        double dpi
) {

    /** What kind of mask to emit from the synthesised phase. */
    public enum OutputType {
        /** 0 / π phase quantised to a 1-bit black/white amplitude-carrier mask. */
        BINARY_PHASE,
        /** Continuous phase mapped linearly to 0..255 greyscale. */
        GREYSCALE_PHASE
    }

    public HologramParameters {
        if (targetImage == null) throw new IllegalArgumentException("targetImage required");
        int w = targetImage.getWidth();
        int h = targetImage.getHeight();
        if (w != h) throw new IllegalArgumentException("targetImage must be square (got " + w + "x" + h + ")");
        if ((w & (w - 1)) != 0) throw new IllegalArgumentException("targetImage side must be power-of-two");
        if (w < 16 || w > 1024) throw new IllegalArgumentException("targetImage side must be 16…1024");
        if (iterations < 1 || iterations > 1000) throw new IllegalArgumentException("iterations 1..1000");
        if (outputType == null) throw new IllegalArgumentException("outputType required");
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");
    }

    /** Convenience: synthetic checker target for tests / demos. */
    public static BufferedImage syntheticCheckerTarget(int n, int blocks) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster r = img.getRaster();
        int b = Math.max(1, n / blocks);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean on = ((x / b) + (y / b)) % 2 == 0;
                r.setSample(x, y, 0, on ? 220 : 20);
            }
        }
        return img;
    }
}

package org.fresnel.optics;

import java.awt.image.BufferedImage;

/**
 * Result of rendering a zone plate: a {@link BufferedImage} together with the
 * physical pixel size in millimeters.
 */
public record RenderResult(
        BufferedImage image,
        double pixelSizeMm
) {

    /** Image width in pixels. */
    public int widthPx() { return image.getWidth(); }
    /** Image height in pixels. */
    public int heightPx() { return image.getHeight(); }
    /** Image width in millimeters. */
    public double widthMm() { return widthPx() * pixelSizeMm; }
    /** Image height in millimeters. */
    public double heightMm() { return heightPx() * pixelSizeMm; }
}

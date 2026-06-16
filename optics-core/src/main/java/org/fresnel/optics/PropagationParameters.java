package org.fresnel.optics;

import java.awt.image.BufferedImage;

/**
 * Parameters for the scalar optical propagation in {@link PropagationSimulator}.
 *
 * @param maskImage    the rendered print mask (greyscale, any size)
 * @param maskType     how the greyscale values encode the field
 *                     ({@link MaskType#BINARY_AMPLITUDE}: 0→opaque, 255→transparent;
 *                      {@link MaskType#GREYSCALE_PHASE}: pixel encodes 0…2π phase,
 *                      unit amplitude everywhere inside the aperture)
 * @param pixelSizeMm  physical size of one mask pixel in millimetres
 * @param wavelengthNm illumination wavelength in nanometres
 * @param zMm          propagation distance in millimetres
 *                     (ignored for {@link PropagationMode#FRAUNHOFER})
 * @param mode         propagation algorithm
 */
public record PropagationParameters(
        BufferedImage maskImage,
        MaskType maskType,
        double pixelSizeMm,
        double wavelengthNm,
        double zMm,
        PropagationMode mode
) {

    public PropagationParameters {
        if (maskImage == null) throw new IllegalArgumentException("maskImage must not be null");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (pixelSizeMm <= 0) throw new IllegalArgumentException("pixelSizeMm must be > 0 (millimeters)");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0 (nanometers)");
        if (zMm <= 0) throw new IllegalArgumentException("zMm must be > 0 (millimeters)");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
    }
}

package org.fresnel.optics;

import java.awt.image.BufferedImage;

/**
 * Parameters for the scalar optical propagation in {@link PropagationSimulator}.
 *
 * @param maskImage          the rendered print mask (greyscale, any size)
 * @param maskType           how the greyscale values encode the field:
 *                           <ul>
 *                             <li>{@link MaskType#BINARY_AMPLITUDE}: pixel 0 → opaque,
 *                                 pixel 255 → transparent; the aperture boundary is already
 *                                 encoded in the pixel values (outside-aperture pixels are 0).</li>
 *                             <li>{@link MaskType#GREYSCALE_PHASE}: inside the circular
 *                                 aperture of diameter {@code apertureDiameterMm}, the field
 *                                 has unit amplitude and phase = pixel/255 × 2π (phase 0 at
 *                                 pixel 0 is a valid in-aperture value); outside the aperture
 *                                 the field is zero regardless of the pixel value.</li>
 *                           </ul>
 * @param pixelSizeMm        physical size of one mask pixel in millimetres (must be &gt; 0)
 * @param apertureDiameterMm diameter of the circular aperture in millimetres (must be &gt; 0);
 *                           used by {@link PropagationSimulator} to determine the aperture
 *                           boundary for {@link MaskType#GREYSCALE_PHASE} masks independently
 *                           of the pixel values
 * @param wavelengthNm       illumination wavelength in nanometres (must be &gt; 0)
 * @param zMm                propagation distance in millimetres (must be &gt; 0;
 *                           accepted but not used for {@link PropagationMode#FRAUNHOFER})
 * @param mode               propagation algorithm
 */
public record PropagationParameters(
        BufferedImage maskImage,
        MaskType maskType,
        double pixelSizeMm,
        double apertureDiameterMm,
        double wavelengthNm,
        double zMm,
        PropagationMode mode
) {

    public PropagationParameters {
        if (maskImage == null) throw new IllegalArgumentException("maskImage must not be null");
        if (maskType == null) throw new IllegalArgumentException("maskType must not be null");
        if (pixelSizeMm <= 0) throw new IllegalArgumentException("pixelSizeMm must be > 0 (millimeters)");
        if (apertureDiameterMm <= 0) throw new IllegalArgumentException("apertureDiameterMm must be > 0 (millimeters)");
        if (wavelengthNm <= 0) throw new IllegalArgumentException("wavelengthNm must be > 0 (nanometers)");
        if (zMm <= 0) throw new IllegalArgumentException("zMm must be > 0 (millimeters)");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
    }
}

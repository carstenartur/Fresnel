package org.fresnel.backend.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.HologramParameters;

/**
 * REST request body for hologram synthesis. The target image is supplied as a
 * base64-encoded PNG / JPEG (so JSON requests work without multipart forms);
 * server-side it is decoded, converted to greyscale, and resized to the requested
 * power-of-two square side via nearest-neighbour sampling.
 */
public record HologramRequest(
        @NotNull String targetImageBase64,
        @NotNull @Min(16) Integer sidePx,
        @NotNull @Min(1) Integer iterations,
        HologramParameters.OutputType outputType,
        @NotNull @Positive Double dpi
) {}

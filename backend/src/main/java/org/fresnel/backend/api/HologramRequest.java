package org.fresnel.backend.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.HologramParameters;

import java.io.IOException;

/**
 * REST and canonical-job request body for hologram synthesis. The target image is
 * supplied as bounded base64-encoded PNG/JPEG data, inspected before pixel decoding,
 * converted to greyscale and resized to the requested power-of-two square side.
 */
public record HologramRequest(
        @NotNull String targetImageBase64,
        @NotNull @Min(16) Integer sidePx,
        @NotNull @Min(1) Integer iterations,
        HologramParameters.OutputType outputType,
        @NotNull @Positive Double dpi,
        @Positive Double wavelengthNm,
        @Positive Double refractiveIndexDelta,
        @Positive Double maxPhaseShiftRad
) {
    private static final String LEGACY_EMPTY_TARGET_BASE64 = "AA==";

    public HologramRequest {
        // Empty data remains a valid UI default until the user chooses an image.
        // Early Hologram design fixtures represented the same state as one zero
        // byte. Preserve that exact harmless legacy value so old documents can be
        // loaded and edited; a real render still routes through the strict decoder.
        boolean legacyEmptyTarget = LEGACY_EMPTY_TARGET_BASE64.equals(targetImageBase64);

        // Every actual embedded source is format/dimension checked without reading
        // its pixel raster. This protects REST calls and canonical job execution,
        // including SOURCE outputs that later preserve the original dimensions.
        if (targetImageBase64 != null
                && !targetImageBase64.isBlank()
                && !legacyEmptyTarget) {
            try {
                HologramImageDecoder.validate(targetImageBase64);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Hologram target image could not be inspected", e);
            }
        }
    }

    public double resolvedWavelengthNm() {
        return wavelengthNm == null ? 550.0 : wavelengthNm;
    }

    public double resolvedRefractiveIndexDelta() {
        return refractiveIndexDelta == null ? 0.5 : refractiveIndexDelta;
    }

    public double resolvedMaxPhaseShiftRad() {
        return maxPhaseShiftRad == null ? 2.0 * Math.PI : maxPhaseShiftRad;
    }
}

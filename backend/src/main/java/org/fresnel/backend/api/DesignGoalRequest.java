package org.fresnel.backend.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.DesignGoal;

/**
 * REST request body for {@code POST /api/assistant/recommend}.
 *
 * @param dpi              printer resolution in dots per inch
 * @param pageSizeWidthMm  printable page width in millimetres (e.g. 210 for A4 portrait)
 * @param pageSizeHeightMm printable page height in millimetres (e.g. 297 for A4 portrait)
 * @param wavelengthNm     design wavelength in nanometres (e.g. 532 for green laser)
 * @param targetFocusMm    desired focal distance in millimetres (e.g. 2000 for 2 m)
 * @param maxApertureMm    optional hard cap on aperture diameter in millimetres
 */
public record DesignGoalRequest(
        @NotNull @Positive Double dpi,
        @NotNull @Positive Double pageSizeWidthMm,
        @NotNull @Positive Double pageSizeHeightMm,
        @NotNull @Positive Double wavelengthNm,
        @NotNull @Positive Double targetFocusMm,
        @Positive Double maxApertureMm
) {
    /** Convert to the optics-core domain model. */
    public DesignGoal toGoal() {
        return new DesignGoal(dpi, pageSizeWidthMm, pageSizeHeightMm,
                wavelengthNm, targetFocusMm, maxApertureMm);
    }
}

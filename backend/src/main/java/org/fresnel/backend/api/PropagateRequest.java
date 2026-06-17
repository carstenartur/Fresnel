package org.fresnel.backend.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fresnel.optics.PropagationMode;

/**
 * REST request body for the {@code POST /api/designs/propagate.png} endpoint.
 *
 * <p>The zone-plate mask is rendered from {@link #base} using
 * {@link SingleZonePlateRequest#toParameters()}, then propagated using the
 * parameters in this record.
 *
 * @param base         zone-plate design parameters (must not be null)
 * @param zMm          propagation distance in millimetres (must be &gt; 0)
 * @param wavelengthNm illumination wavelength in nanometres (must be &gt; 0 if supplied);
 *                     if {@code null}, the wavelength from {@code base} is used
 * @param mode         propagation algorithm; if {@code null}, defaults to
 *                     {@link PropagationMode#FRESNEL_TF}
 */
public record PropagateRequest(
        @NotNull @Valid SingleZonePlateRequest base,
        @NotNull @Positive Double zMm,
        @Positive Double wavelengthNm,
        PropagationMode mode
) {

    /** Resolved wavelength (falls back to {@code base} wavelength when null). */
    public double resolvedWavelengthNm() {
        return wavelengthNm != null ? wavelengthNm : base().wavelengthNm();
    }

    /** Resolved propagation mode (defaults to {@link PropagationMode#FRESNEL_TF}). */
    public PropagationMode resolvedMode() {
        return mode != null ? mode : PropagationMode.FRESNEL_TF;
    }
}

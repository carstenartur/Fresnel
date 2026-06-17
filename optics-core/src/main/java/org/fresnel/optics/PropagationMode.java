package org.fresnel.optics;

/**
 * Scalar diffraction propagation mode for {@link PropagationSimulator}.
 *
 * <ul>
 *   <li>{@link #FRAUNHOFER} – far-field intensity: {@code |FFT(field)|²} with a
 *       centred (fftshifted) output. Best for inspecting the diffraction pattern at the
 *       focal plane when the Fraunhofer condition {@code z ≫ D²/λ} is satisfied.  No
 *       physical propagation distance is used; the output axes represent spatial
 *       frequencies, not a physical plane at a specific distance.</li>
 *   <li>{@link #FRESNEL_TF} – angular-spectrum / transfer-function propagation.  The
 *       field is multiplied in the frequency domain by the exact free-space transfer
 *       function {@code H(fx,fy) = exp(i·2π/λ·z·√(1–(λfx)²–(λfy)²))} (evanescent
 *       components are zeroed).  This is valid for any propagation distance and
 *       correctly predicts the intensity at the physical plane a distance {@code zMm}
 *       from the mask.</li>
 * </ul>
 */
public enum PropagationMode {
    /** Far-field / focal-plane inspection via {@code |FFT|²}. */
    FRAUNHOFER,
    /** Finite-distance propagation via the angular-spectrum transfer function. */
    FRESNEL_TF
}

package org.fresnel.optics;

/** Mask type for the rendered zone plate. */
public enum MaskType {
    /** Binary amplitude mask (1-bit black/white). */
    BINARY_AMPLITUDE,
    /** Continuous greyscale phase representation (0..255). */
    GREYSCALE_PHASE
}

package org.fresnel.optics;

/** Mask polarity: which half of the cosine is transparent. */
public enum Polarity {
    /** Transparent where cos(phi) >= 0. */
    POSITIVE,
    /** Transparent where cos(phi) < 0. */
    NEGATIVE
}

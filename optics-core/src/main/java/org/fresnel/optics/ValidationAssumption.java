package org.fresnel.optics;

/** Assumption or limitation used for deterministic validation. */
public record ValidationAssumption(
        ValidationLayer layer,
        String statement,
        boolean limitation
) {}

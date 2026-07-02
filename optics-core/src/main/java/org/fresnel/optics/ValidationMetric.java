package org.fresnel.optics;

/** A deterministic validation metric. */
public record ValidationMetric(
        ValidationLayer layer,
        String key,
        String label,
        double value,
        String unit
) {}

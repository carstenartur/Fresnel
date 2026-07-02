package org.fresnel.optics;

/** A severity-ranked validation finding. */
public record ValidationFinding(
        ValidationLayer layer,
        String code,
        String message,
        ValidationSeverity severity
) {}

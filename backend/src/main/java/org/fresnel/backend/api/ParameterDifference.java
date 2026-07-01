package org.fresnel.backend.api;

/**
 * A single parameter that differs between two or more design variants.
 *
 * <p>Values are represented as formatted strings so that units can be
 * embedded naturally (e.g. {@code "550.0 nm"}, {@code "BINARY_AMPLITUDE"}).
 *
 * @param parameter  canonical parameter name (e.g. {@code "focalLengthMm"})
 * @param unit       SI unit or empty string for dimensionless / enum parameters
 * @param values     one formatted value per variant in the same order as
 *                   {@link DesignComparisonResult#variants()}
 */
public record ParameterDifference(
        String parameter,
        String unit,
        java.util.List<String> values
) {}

package org.fresnel.backend.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/designs/compare}.
 *
 * <p>At least two variants are required. Each variant must specify a valid
 * {@code pluginId} and the matching parameter object.
 *
 * @param variants the design variants to compare (minimum 2)
 * @param rank     when {@code true} the response includes a deterministic ranking
 *                 of all variants based on optical quality and printability scores
 */
public record DesignComparisonRequest(
        @NotNull @Size(min = 2, message = "At least two variants are required for comparison")
        List<@Valid @NotNull DesignVariant> variants,
        boolean rank
) {}

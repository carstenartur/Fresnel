package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Full comparison result returned by {@code POST /api/designs/compare}.
 *
 * @param variants             per-variant results in the same order as the request
 * @param parameterDifferences parameters that differ across at least two variants,
 *                             in a stable alphabetical order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignComparisonResult(
        List<VariantResult> variants,
        List<ParameterDifference> parameterDifferences
) {

    /**
     * Result for a single design variant.
     *
     * @param label          variant label copied from the request
     * @param pluginId       plugin identifier ({@code "single"} or {@code "rgb"})
     * @param validation     validation response (metrics, quality report, warnings)
     * @param previewBase64  base-64 encoded preview PNG (may be {@code null} if rendering failed)
     * @param previewWidthPx width of the preview image in pixels (scale metadata)
     * @param previewHeightPx height of the preview image in pixels
     * @param pixelsPerMm    physical scale: printer pixels per millimetre
     * @param score          ranking score (non-null only when ranking was requested and all
     *                       required metrics are present)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariantResult(
            String           label,
            String           pluginId,
            ValidationResponse validation,
            String           previewBase64,
            int              previewWidthPx,
            int              previewHeightPx,
            double           pixelsPerMm,
            VariantScore     score
    ) {}
}

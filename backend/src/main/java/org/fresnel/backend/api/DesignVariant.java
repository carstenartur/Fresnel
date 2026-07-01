package org.fresnel.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One design variant submitted as part of a comparison request.
 *
 * <p>The {@code pluginId} discriminates which parameter set to use:
 * <ul>
 *   <li>{@code "single"} – a single Fresnel zone plate ({@link #singleParams} must be set)</li>
 *   <li>{@code "rgb"}    – an RGB zone plate ({@link #rgbParams} must be set)</li>
 * </ul>
 *
 * @param label        human-readable label for this variant (required, non-blank)
 * @param pluginId     design type discriminator ({@code "single"} or {@code "rgb"})
 * @param singleParams zone-plate design parameters (required when pluginId is {@code "single"})
 * @param rgbParams    RGB zone-plate parameters    (required when pluginId is {@code "rgb"})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignVariant(
        @NotBlank String label,
        @NotNull  String pluginId,
        @Valid    SingleZonePlateRequest singleParams,
        @Valid    RgbZonePlateRequest    rgbParams
) {
    /** Plugin-ID constant for a single Fresnel zone plate. */
    public static final String PLUGIN_SINGLE = "single";
    /** Plugin-ID constant for an RGB zone plate. */
    public static final String PLUGIN_RGB    = "rgb";
}

package org.fresnel.optics;

import java.util.Set;

/**
 * Machine-readable metadata record for one Fresnel plugin.
 *
 * <p>A descriptor is the single source of truth for everything that was
 * previously duplicated across Java code, TypeScript UI definitions and
 * documentation: renderer class name, frontend mode key, documentation URL,
 * supported export formats, validation support, etc.
 *
 * <p>Instances are immutable; use {@link PluginRegistry} to obtain them.
 *
 * @param id               stable, lowercase, hyphen-separated identifier suitable
 *                         for use in API URLs (e.g. {@code "zone-plate"})
 * @param displayName      human-readable name shown in the UI
 *                         (e.g. {@code "Zone Plate"})
 * @param description      one-line description of the optical element
 * @param rendererClass    simple class name of the Java renderer or synthesiser
 *                         (e.g. {@code "ZonePlateRenderer"})
 * @param parameterType    simple class name of the parameter record
 *                         (e.g. {@code "SingleZonePlateParameters"})
 * @param frontendModeId   key used in the React {@code MODES} array / route
 *                         (e.g. {@code "single"})
 * @param documentationUrl relative path to the plugin's Markdown doc page
 * @param stability        maturity classification of this plugin
 * @param capabilities     set of {@link PluginCapability} values advertised by
 *                         this plugin; never {@code null}, may be empty
 * @param propagationModes supported {@link PropagationMode} values; empty for
 *                         plugins that do not offer propagation preview
 */
public record PluginDescriptor(
        String id,
        String displayName,
        String description,
        String rendererClass,
        String parameterType,
        String frontendModeId,
        String documentationUrl,
        PluginStabilityLevel stability,
        Set<PluginCapability> capabilities,
        Set<PropagationMode> propagationModes
) {

    /** Defensive copy — ensures the sets stored in the record are immutable. */
    public PluginDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("displayName must not be blank");
        if (stability == null) throw new IllegalArgumentException("stability must not be null");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        propagationModes = propagationModes == null ? Set.of() : Set.copyOf(propagationModes);
    }

    // ---- Convenience capability queries ----

    /** Returns {@code true} if this plugin supports the given capability. */
    public boolean supports(PluginCapability capability) {
        return capabilities.contains(capability);
    }

    /** Returns {@code true} if this plugin can export in at least the given format. */
    public boolean supportsExport(PluginCapability exportCapability) {
        return capabilities.contains(exportCapability);
    }

    /** Returns {@code true} if this plugin provides printability analysis. */
    public boolean supportsPrintabilityAnalysis() {
        return capabilities.contains(PluginCapability.PRINTABILITY_ANALYSIS);
    }

    /** Returns {@code true} if this plugin provides an optical quality report. */
    public boolean supportsOpticalQualityReport() {
        return capabilities.contains(PluginCapability.OPTICAL_QUALITY_REPORT);
    }

    /** Returns {@code true} if this plugin provides experimental validation. */
    public boolean supportsExperimentalValidation() {
        return capabilities.contains(PluginCapability.EXPERIMENTAL_VALIDATION);
    }

    /** Returns {@code true} if this plugin supports propagation preview. */
    public boolean supportsPropagationPreview() {
        return capabilities.contains(PluginCapability.PROPAGATION_PREVIEW);
    }

    /** Returns {@code true} if this plugin supports the given propagation mode. */
    public boolean supports(PropagationMode mode) {
        return propagationModes.contains(mode);
    }
}

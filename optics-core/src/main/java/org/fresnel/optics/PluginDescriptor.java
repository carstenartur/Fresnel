package org.fresnel.optics;

import java.util.Set;

/**
 * Machine-readable metadata record for one Fresnel plugin.
 *
 * <p>A descriptor is the single source of truth for renderer metadata,
 * documentation, supported capabilities and versioned editor schemas. Stable
 * plugin IDs are also the public route and job-file contract; frontend-only mode
 * aliases are deliberately not part of this model.</p>
 *
 * <p>Instances are immutable; use {@link PluginRegistry} to obtain them.</p>
 *
 * @param id               stable, lowercase, hyphen-separated identifier suitable
 *                         for use in API URLs (e.g. {@code "zone-plate"})
 * @param displayName      human-readable name shown in the UI
 * @param description      one-line description of the optical element
 * @param rendererClass    simple class name of the Java renderer or synthesiser
 * @param parameterType    simple class name of the parameter record
 * @param documentationUrl relative path to the plugin's Markdown documentation
 * @param stability        maturity classification of this plugin
 * @param capabilities     immutable advertised capability set
 * @param propagationModes supported propagation modes, empty when unavailable
 * @param schema           versioned parameter/UI schema resources and editor mode
 */
public record PluginDescriptor(
        String id,
        String displayName,
        String description,
        String rendererClass,
        String parameterType,
        String documentationUrl,
        PluginStabilityLevel stability,
        Set<PluginCapability> capabilities,
        Set<PropagationMode> propagationModes,
        PluginSchemaDescriptor schema
) {

    /** Defensive validation and copies keep registry data immutable and complete. */
    public PluginDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("displayName must not be blank");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("description must not be blank");
        if (rendererClass == null || rendererClass.isBlank())
            throw new IllegalArgumentException("rendererClass must not be blank");
        if (parameterType == null || parameterType.isBlank())
            throw new IllegalArgumentException("parameterType must not be blank");
        if (documentationUrl == null || documentationUrl.isBlank())
            throw new IllegalArgumentException("documentationUrl must not be blank");
        if (stability == null) throw new IllegalArgumentException("stability must not be null");
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        propagationModes = propagationModes == null ? Set.of() : Set.copyOf(propagationModes);
    }

    /** Returns {@code true} if this plugin supports the given capability. */
    public boolean supports(PluginCapability capability) {
        return capabilities.contains(capability);
    }

    /** Returns {@code true} if this plugin can export in the requested format. */
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

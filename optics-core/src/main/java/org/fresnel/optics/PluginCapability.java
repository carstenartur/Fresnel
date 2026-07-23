package org.fresnel.optics;

/**
 * Capabilities that a Fresnel plugin can advertise in the machine-readable registry.
 *
 * <p>The enum is intentionally explicit rather than using arbitrary strings so callers
 * can discover supported outputs and validation layers without probing endpoints.</p>
 */
public enum PluginCapability {
    EXPORT_PNG,
    EXPORT_SVG,
    EXPORT_PDF,
    EXPORT_PCL,
    EXPORT_DXF,
    EXPORT_GERBER,
    EXPORT_STL,
    PREVIEW_PNG,
    PROPAGATION_PREVIEW,
    PRINTABILITY_ANALYSIS,
    OPTICAL_QUALITY_REPORT,
    EXPERIMENTAL_VALIDATION
}

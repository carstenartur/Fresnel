package org.fresnel.optics;

/**
 * A capability that a Fresnel plugin may advertise in its {@link PluginDescriptor}.
 *
 * <p>Capabilities are used by the registry for targeted queries such as
 * "list all plugins that can export to PDF" or "which plugins support
 * printability analysis".
 */
public enum PluginCapability {

    // ---- Export formats ----

    /** Plugin can render and export a PNG raster image. */
    EXPORT_PNG,

    /** Plugin can render and export an SVG vector file. */
    EXPORT_SVG,

    /** Plugin can render and export a PDF print sheet. */
    EXPORT_PDF,

    /** Plugin can export a DXF CAD file. */
    EXPORT_DXF,

    /** Plugin can export a Gerber PCB/photoplotter file. */
    EXPORT_GERBER,

    /** Plugin can export an STL 3-D relief mesh. */
    EXPORT_STL,

    // ---- Preview types ----

    /** Plugin supports live PNG preview rendering. */
    PREVIEW_PNG,

    /** Plugin supports scalar-diffraction propagation preview. */
    PROPAGATION_PREVIEW,

    // ---- Validation and analysis ----

    /** Plugin supports printability validation (outermost-zone analysis). */
    PRINTABILITY_ANALYSIS,

    /** Plugin exposes an optical-quality report (NA, f-number, Airy disk, …). */
    OPTICAL_QUALITY_REPORT,

    /** Plugin supports experimental (beyond standard) validation procedures. */
    EXPERIMENTAL_VALIDATION,
}

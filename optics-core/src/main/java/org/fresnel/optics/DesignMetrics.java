package org.fresnel.optics;

/**
 * Printability and quality metrics for a Fresnel zone plate design.
 *
 * @param outerZoneWidthMicrons   width of the outermost (smallest) Fresnel zone in micrometers
 * @param printerPixelMicrons     printer pixel size in micrometers
 * @param pixelsPerOuterZone      printer pixels per outermost zone
 * @param estimatedTransmission   approximate average transmission of a binary amplitude mask (~0.5)
 * @param estimatedFirstOrderEfficiency approximate first-order diffraction efficiency
 * @param numberOfZones           total number of Fresnel zones across the aperture radius
 */
public record DesignMetrics(
        double outerZoneWidthMicrons,
        double printerPixelMicrons,
        double pixelsPerOuterZone,
        double estimatedTransmission,
        double estimatedFirstOrderEfficiency,
        int numberOfZones
) {}

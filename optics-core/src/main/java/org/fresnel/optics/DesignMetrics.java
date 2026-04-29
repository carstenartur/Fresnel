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
 * @param chromaticShifts         focal length at sample wavelengths (paraxial diffractive, f∝1/λ)
 * @param defocusBlurs            defocus circle-of-confusion at sample wall distances
 */
public record DesignMetrics(
        double outerZoneWidthMicrons,
        double printerPixelMicrons,
        double pixelsPerOuterZone,
        double estimatedTransmission,
        double estimatedFirstOrderEfficiency,
        int numberOfZones,
        java.util.List<ChromaticShift> chromaticShifts,
        java.util.List<DefocusEntry> defocusBlurs
) {

    /** Compact constructor copies the lists defensively and tolerates nulls. */
    public DesignMetrics {
        chromaticShifts = chromaticShifts == null ? java.util.List.of() : java.util.List.copyOf(chromaticShifts);
        defocusBlurs    = defocusBlurs    == null ? java.util.List.of() : java.util.List.copyOf(defocusBlurs);
    }

    /** Backwards-compatible constructor (no chromatic / defocus tables). */
    public DesignMetrics(double outerZoneWidthMicrons, double printerPixelMicrons,
                         double pixelsPerOuterZone, double estimatedTransmission,
                         double estimatedFirstOrderEfficiency, int numberOfZones) {
        this(outerZoneWidthMicrons, printerPixelMicrons, pixelsPerOuterZone,
                estimatedTransmission, estimatedFirstOrderEfficiency, numberOfZones,
                java.util.List.of(), java.util.List.of());
    }

    /** Focal length at a different wavelength than the design wavelength. */
    public record ChromaticShift(double wavelengthNm, double focalLengthMm) {}

    /** Defocus blur diameter when projecting onto a wall at a given distance. */
    public record DefocusEntry(double wallDistanceMm, double blurDiameterMm) {}
}

package org.fresnel.optics;

import java.time.Instant;

/**
 * Reusable, orientation-specific measurement record for one printed calibration target.
 * A complete printer/material characterization consists of two independent records.
 */
public record PrinterCalibrationResult(
        String printerModel,
        String printerProfileId,
        int printerProfileVersion,
        String mediumDescription,
        String qualityMode,
        int nominalDpiX,
        int nominalDpiY,
        PclPageOrientation pageOrientation,
        DeviceAxis pageXAxisMapsTo,
        DeviceAxis pageYAxisMapsTo,
        LineOrientation lineOrientation,
        DeviceAxis testedDeviceAxis,
        Double observedDegradationPositionMm,
        Double firstResolvedPitchUm,
        Double minimumUsefulFeatureWidthUm,
        Double firstResolvedLinesPerMm,
        Double effectiveDpi,
        String observationNotes,
        String measurementAttachmentReference,
        Instant measuredAt
) {
    public PrinterCalibrationResult {
        printerModel = requireText(printerModel, "printerModel");
        printerProfileId = requireText(printerProfileId, "printerProfileId");
        mediumDescription = requireText(mediumDescription, "mediumDescription");
        qualityMode = requireText(qualityMode, "qualityMode");
        if (printerProfileVersion <= 0) {
            throw new IllegalArgumentException("printerProfileVersion must be positive");
        }
        if (nominalDpiX <= 0 || nominalDpiY <= 0) {
            throw new IllegalArgumentException("nominal printer DPI must be positive on both axes");
        }
        if (pageOrientation == null || pageXAxisMapsTo == null || pageYAxisMapsTo == null) {
            throw new IllegalArgumentException("page orientation and axis mapping must not be null");
        }
        if (pageXAxisMapsTo == pageYAxisMapsTo) {
            throw new IllegalArgumentException("page X and page Y must map to different device axes");
        }
        if (lineOrientation == null || testedDeviceAxis == null) {
            throw new IllegalArgumentException("line orientation and tested device axis must not be null");
        }
        DeviceAxis expectedAxis = lineOrientation == LineOrientation.VERTICAL
                ? pageXAxisMapsTo
                : pageYAxisMapsTo;
        if (testedDeviceAxis != expectedAxis) {
            throw new IllegalArgumentException(
                    lineOrientation + " lines test mapped device axis " + expectedAxis
                            + ", not " + testedDeviceAxis);
        }
        requireOptionalNonNegative(observedDegradationPositionMm,
                "observedDegradationPositionMm");
        requireOptionalPositive(firstResolvedPitchUm, "firstResolvedPitchUm");
        requireOptionalPositive(minimumUsefulFeatureWidthUm, "minimumUsefulFeatureWidthUm");
        requireOptionalPositive(firstResolvedLinesPerMm, "firstResolvedLinesPerMm");
        requireOptionalPositive(effectiveDpi, "effectiveDpi");
        observationNotes = observationNotes == null ? "" : observationNotes.trim();
        measurementAttachmentReference = measurementAttachmentReference == null
                ? ""
                : measurementAttachmentReference.trim();
        if (measuredAt == null) throw new IllegalArgumentException("measuredAt must not be null");
    }

    public static PrinterCalibrationResult fromProfile(
            String printerModel,
            PrinterRasterProfile profile,
            String mediumDescription,
            String qualityMode,
            LineOrientation lineOrientation,
            Double observedDegradationPositionMm,
            Double firstResolvedPitchUm,
            Double minimumUsefulFeatureWidthUm,
            Double firstResolvedLinesPerMm,
            Double effectiveDpi,
            String observationNotes,
            String measurementAttachmentReference,
            Instant measuredAt) {
        if (profile == null) throw new IllegalArgumentException("profile must not be null");
        return new PrinterCalibrationResult(
                printerModel,
                profile.id(),
                profile.version(),
                mediumDescription,
                qualityMode,
                profile.dpiX(),
                profile.dpiY(),
                profile.pageOrientation(),
                profile.pageXAxisMapsTo(),
                profile.pageYAxisMapsTo(),
                lineOrientation,
                profile.testedDeviceAxis(lineOrientation),
                observedDegradationPositionMm,
                firstResolvedPitchUm,
                minimumUsefulFeatureWidthUm,
                firstResolvedLinesPerMm,
                effectiveDpi,
                observationNotes,
                measurementAttachmentReference,
                measuredAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void requireOptionalPositive(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException(name + " must be finite and positive when supplied");
        }
    }

    private static void requireOptionalNonNegative(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0.0)) {
            throw new IllegalArgumentException(name + " must be finite and non-negative when supplied");
        }
    }
}

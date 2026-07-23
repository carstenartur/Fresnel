package org.fresnel.optics;

import java.time.Instant;

/** Reusable measured printer-resolution record for future validation and experiments. */
public record PrinterCalibrationResult(
        String printerProfileId,
        int printerProfileVersion,
        LineOrientation lineOrientation,
        DeviceAxis testedDeviceAxis,
        Double firstResolvedPitchUm,
        Double firstResolvedLinesPerMm,
        Double effectiveDpi,
        String observationNotes,
        Instant measuredAt
) {
    public PrinterCalibrationResult {
        if (printerProfileId == null || printerProfileId.isBlank()) {
            throw new IllegalArgumentException("printerProfileId must not be blank");
        }
        if (printerProfileVersion <= 0) {
            throw new IllegalArgumentException("printerProfileVersion must be positive");
        }
        if (lineOrientation == null || testedDeviceAxis == null) {
            throw new IllegalArgumentException("orientation and tested device axis must not be null");
        }
        requireOptionalPositive(firstResolvedPitchUm, "firstResolvedPitchUm");
        requireOptionalPositive(firstResolvedLinesPerMm, "firstResolvedLinesPerMm");
        requireOptionalPositive(effectiveDpi, "effectiveDpi");
        observationNotes = observationNotes == null ? "" : observationNotes.trim();
    }

    private static void requireOptionalPositive(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException(name + " must be finite and positive when supplied");
        }
    }
}

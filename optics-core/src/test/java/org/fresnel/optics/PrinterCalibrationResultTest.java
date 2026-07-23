package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrinterCalibrationResultTest {

    @Test
    void fromProfileCapturesTheOrientationSpecificDeviceAxisAndMetadata() {
        PrinterRasterProfile profile = PrinterRasterProfiles.PCL5E_A4_600_PORTRAIT;
        PrinterCalibrationResult result = PrinterCalibrationResult.fromProfile(
                "Example Printer",
                profile,
                "Transparent film",
                "Maximum quality",
                LineOrientation.VERTICAL,
                131.5,
                84.0,
                42.0,
                1000.0 / 84.0,
                25_400.0 / 42.0,
                "Repeatable under transmitted light.",
                "photos/vertical.jpg",
                Instant.parse("2026-07-23T18:00:00Z"));

        assertEquals(profile.id(), result.printerProfileId());
        assertEquals(profile.version(), result.printerProfileVersion());
        assertEquals(profile.dpiX(), result.nominalDpiX());
        assertEquals(profile.dpiY(), result.nominalDpiY());
        assertEquals(LineOrientation.VERTICAL, result.lineOrientation());
        assertEquals(DeviceAxis.X, result.testedDeviceAxis());
        assertEquals("photos/vertical.jpg", result.measurementAttachmentReference());
    }

    @Test
    void inconsistentOrientationAndDeviceAxisAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrinterCalibrationResult(
                "Example Printer",
                PrinterRasterProfiles.DEFAULT_PROFILE_ID,
                1,
                "Transparent film",
                "Maximum quality",
                600,
                600,
                PclPageOrientation.PORTRAIT,
                DeviceAxis.X,
                DeviceAxis.Y,
                LineOrientation.VERTICAL,
                DeviceAxis.Y,
                100.0,
                80.0,
                40.0,
                12.5,
                635.0,
                "",
                "",
                Instant.parse("2026-07-23T18:00:00Z")));
    }
}

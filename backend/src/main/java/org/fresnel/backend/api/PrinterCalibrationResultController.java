package org.fresnel.backend.api;

import org.fresnel.optics.PrinterCalibrationResult;
import org.fresnel.optics.PrinterRasterProfile;
import org.fresnel.optics.PrinterRasterProfiles;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Validates and exports reusable orientation-specific printer calibration results. */
@RestController
@RequestMapping("/api/designs/variable-line-grating/calibration-results")
public class PrinterCalibrationResultController {

    private final ObjectMapper mapper;

    public PrinterCalibrationResultController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping(
            value = "/export.json",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportJson(@RequestBody PrinterCalibrationResult result) {
        PrinterRasterProfile profile = PrinterRasterProfiles.require(result.printerProfileId());
        requireProfileSnapshotMatches(result, profile);
        final byte[] content;
        try {
            content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize printer calibration result", e);
        }
        String orientation = result.lineOrientation().name().toLowerCase(Locale.ROOT);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("fresnel-printer-calibration-" + orientation + ".json")
                .build());
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private static void requireProfileSnapshotMatches(
            PrinterCalibrationResult result,
            PrinterRasterProfile profile) {
        if (result.printerProfileVersion() != profile.version()
                || result.nominalDpiX() != profile.dpiX()
                || result.nominalDpiY() != profile.dpiY()
                || result.pageOrientation() != profile.pageOrientation()
                || result.pageXAxisMapsTo() != profile.pageXAxisMapsTo()
                || result.pageYAxisMapsTo() != profile.pageYAxisMapsTo()) {
            throw new IllegalArgumentException(
                    "calibration result printer metadata does not match trusted profile " + profile.id()
                            + " version " + profile.version());
        }
    }
}

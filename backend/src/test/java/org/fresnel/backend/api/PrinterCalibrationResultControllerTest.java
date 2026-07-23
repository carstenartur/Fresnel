package org.fresnel.backend.api;

import org.fresnel.optics.DeviceAxis;
import org.fresnel.optics.LineOrientation;
import org.fresnel.optics.PclPageOrientation;
import org.fresnel.optics.PrinterCalibrationResult;
import org.fresnel.optics.PrinterRasterProfile;
import org.fresnel.optics.PrinterRasterProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PrinterCalibrationResultControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void exportsAProfileVerifiedOrientationSpecificResult() throws Exception {
        PrinterRasterProfile profile = PrinterRasterProfiles.PCL5E_A4_600_PORTRAIT;
        PrinterCalibrationResult result = PrinterCalibrationResult.fromProfile(
                "Example Printer",
                profile,
                "Transparent film",
                "Maximum quality",
                LineOrientation.HORIZONTAL,
                115.2,
                92.0,
                46.0,
                1000.0 / 92.0,
                25_400.0 / 46.0,
                "Horizontal result.",
                "photos/horizontal.jpg",
                Instant.parse("2026-07-23T18:00:00Z"));

        mvc.perform(post("/api/designs/variable-line-grating/calibration-results/export.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(result)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition",
                        containsString("fresnel-printer-calibration-horizontal.json")))
                .andExpect(jsonPath("$.printerProfileId").value(profile.id()))
                .andExpect(jsonPath("$.lineOrientation").value("HORIZONTAL"))
                .andExpect(jsonPath("$.testedDeviceAxis").value("Y"))
                .andExpect(jsonPath("$.measurementAttachmentReference")
                        .value("photos/horizontal.jpg"));
    }

    @Test
    void rejectsAResultWhoseProfileSnapshotWasTamperedWith() throws Exception {
        PrinterCalibrationResult tampered = new PrinterCalibrationResult(
                "Example Printer",
                PrinterRasterProfiles.DEFAULT_PROFILE_ID,
                1,
                "Transparent film",
                "Maximum quality",
                1200,
                600,
                PclPageOrientation.PORTRAIT,
                DeviceAxis.X,
                DeviceAxis.Y,
                LineOrientation.VERTICAL,
                DeviceAxis.X,
                100.0,
                80.0,
                40.0,
                12.5,
                635.0,
                "",
                "",
                Instant.parse("2026-07-23T18:00:00Z"));

        mvc.perform(post("/api/designs/variable-line-grating/calibration-results/export.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(tampered)))
                .andExpect(status().isBadRequest());
    }
}

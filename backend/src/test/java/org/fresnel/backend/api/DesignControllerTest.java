package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DesignControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void validateReturnsMetricsForGoodDesign() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 20.0,
                  "focalLengthMm": 5000.0,
                  "wavelengthNm": 550.0,
                  "dpi": 2400.0
                }
                """;
        mvc.perform(post("/api/designs/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.metrics.outerZoneWidthMicrons",
                        org.hamcrest.Matchers.closeTo(137.5, 1e-3)))
                .andExpect(jsonPath("$.metrics.printerPixelMicrons").exists());
    }

    @Test
    void validateFlagsCriticalOuterZone() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 20.0,
                  "focalLengthMm": 100.0,
                  "wavelengthNm": 550.0,
                  "dpi": 2400.0
                }
                """;
        mvc.perform(post("/api/designs/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.warnings[0].code").value("OUTER_ZONE_TOO_SMALL"));
    }

    @Test
    void validateRejectsInvalidInput() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": -1.0,
                  "focalLengthMm": 50.0,
                  "wavelengthNm": 550.0,
                  "dpi": 600.0
                }
                """;
        mvc.perform(post("/api/designs/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewReturnsPng() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 5.0,
                  "focalLengthMm": 50.0,
                  "wavelengthNm": 550.0,
                  "dpi": 600.0
                }
                """;
        mvc.perform(post("/api/designs/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void previewRejectsOversizedRender() throws Exception {
        // 100 mm @ 4800 dpi => ~18 900 px, above the 4096 cap
        String body = """
                {
                  "apertureDiameterMm": 100.0,
                  "focalLengthMm": 5000.0,
                  "wavelengthNm": 550.0,
                  "dpi": 4800.0
                }
                """;
        mvc.perform(post("/api/designs/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge());
    }
}

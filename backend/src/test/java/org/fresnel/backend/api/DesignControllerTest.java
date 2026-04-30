package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
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

    @Test
    void validateExposesChromaticAndDefocusTables() throws Exception {
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
                .andExpect(jsonPath("$.metrics.chromaticShifts").isArray())
                .andExpect(jsonPath("$.metrics.chromaticShifts[0].wavelengthNm").exists())
                .andExpect(jsonPath("$.metrics.defocusBlurs").isArray())
                .andExpect(jsonPath("$.metrics.defocusBlurs[0].wallDistanceMm").exists());
    }

    @Test
    void hexPreviewReturnsPng() throws Exception {
        String body = """
                {
                  "macroRadiusMm": 10.0,
                  "subDiameterMm": 4.0,
                  "subPitchMm": 4.5,
                  "focalLengthMm": 1000.0,
                  "wavelengthNm": 550.0,
                  "dpi": 300.0
                }
                """;
        mvc.perform(post("/api/designs/hex/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void hexInfoReportsSubElementCount() throws Exception {
        String body = """
                {
                  "macroRadiusMm": 15.0,
                  "subDiameterMm": 5.0,
                  "subPitchMm": 5.0,
                  "focalLengthMm": 1000.0,
                  "wavelengthNm": 550.0,
                  "dpi": 300.0
                }
                """;
        mvc.perform(post("/api/designs/hex/info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subElements").isNumber())
                .andExpect(jsonPath("$.imageSidePx").isNumber());
    }

    @Test
    void foilPreviewReturnsPng() throws Exception {
        String body = """
                {
                  "sheetWidthMm": 100.0,
                  "sheetHeightMm": 60.0,
                  "macroRadiusMm": 15.0,
                  "subDiameterMm": 4.0,
                  "subPitchMm": 4.5,
                  "wavelengthNm": 550.0,
                  "dpi": 150.0,
                  "drawCropMarks": true
                }
                """;
        mvc.perform(post("/api/designs/foil/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void foilExportPdfReturnsPdf() throws Exception {
        String body = """
                {
                  "sheetWidthMm": 60.0,
                  "sheetHeightMm": 60.0,
                  "macroRadiusMm": 15.0,
                  "subDiameterMm": 4.0,
                  "subPitchMm": 4.5,
                  "wavelengthNm": 550.0,
                  "dpi": 100.0
                }
                """;
        mvc.perform(post("/api/designs/foil/export.pdf?sheet=A4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void multiFocusPreviewReturnsPng() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 5.0,
                  "wavelengthNm": 550.0,
                  "dpi": 300.0,
                  "focusPoints": [
                    {"xMm": -2.0, "yMm": 0.0, "zMm": 1000.0},
                    {"xMm":  2.0, "yMm": 0.0, "zMm": 1000.0}
                  ]
                }
                """;
        mvc.perform(post("/api/designs/multifocus/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void multiFocusRejectsEmptyFocusPoints() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 5.0,
                  "wavelengthNm": 550.0,
                  "dpi": 300.0,
                  "focusPoints": []
                }
                """;
        mvc.perform(post("/api/designs/multifocus/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rgbPreviewReturnsPng() throws Exception {
        String body = """
                {
                  "base": {
                    "apertureDiameterMm": 4.0,
                    "focalLengthMm": 50.0,
                    "wavelengthNm": 550.0,
                    "dpi": 300.0
                  },
                  "redNm": 630.0,
                  "greenNm": 532.0,
                  "blueNm": 450.0
                }
                """;
        mvc.perform(post("/api/designs/rgb/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void exportSvgReturnsSvg() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 5.0,
                  "focalLengthMm": 100.0,
                  "wavelengthNm": 550.0,
                  "dpi": 600.0
                }
                """;
        mvc.perform(post("/api/designs/export.svg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("image/svg+xml")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")));
    }

    @Test
    void exportPdfReturnsPdf() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 50.0,
                  "focalLengthMm": 500.0,
                  "wavelengthNm": 550.0,
                  "dpi": 100.0
                }
                """;
        mvc.perform(post("/api/designs/export.pdf?sheet=A4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }
}

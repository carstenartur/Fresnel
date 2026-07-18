package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WindowFoilExportControllerTest {

    private static final String VALID_REQUEST = """
            {
              "sheetWidthMm": 60.0,
              "sheetHeightMm": 40.0,
              "macroRadiusMm": 12.0,
              "subDiameterMm": 4.0,
              "subPitchMm": 4.5,
              "wavelengthNm": 550.0,
              "dpi": 200.0,
              "maskType": "BINARY_AMPLITUDE",
              "polarity": "POSITIVE",
              "cellSpecs": [
                {
                  "focalLengthMm": 1000.0,
                  "targetOffsetXmm": 0.0,
                  "targetOffsetYmm": 0.0
                }
              ],
              "drawCropMarks": true
            }
            """;

    @Autowired MockMvc mvc;

    @Test
    void advertisedPngExportReturnsTheProductionResolutionImage() throws Exception {
        MvcResult result = mvc.perform(post("/api/designs/foil/export.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.IMAGE_PNG)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andExpect(header().string(
                        "Content-Disposition",
                        containsString("fresnel-window-foil.png")))
                .andReturn();

        byte[] content = result.getResponse().getContentAsByteArray();
        assertTrue(content.length > 100);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        assertNotNull(image);
        assertTrue(image.getWidth() >= 470);
        assertTrue(image.getHeight() >= 310);
    }

    @Test
    void pngExportRejectsInvalidWindowFoilParameters() throws Exception {
        mvc.perform(post("/api/designs/foil/export.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST.replace("\"subPitchMm\": 4.5", "\"subPitchMm\": -1")))
                .andExpect(status().isBadRequest());
    }
}

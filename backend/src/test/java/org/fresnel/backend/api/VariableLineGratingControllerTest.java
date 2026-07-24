package org.fresnel.backend.api;

import org.fresnel.optics.PclExporter;
import org.fresnel.optics.PrinterRasterProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VariableLineGratingControllerTest {

    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");
    private static final MediaType PCL = MediaType.parseMediaType(PclExporter.MEDIA_TYPE);

    private static final String VALID = """
            {
              "widthMm": 20.0,
              "heightMm": 20.0,
              "lineOrientation": "VERTICAL",
              "startPitchUm": 1000.0,
              "endPitchUm": 500.0,
              "progression": "LINEAR_PITCH",
              "progressionDirection": "NORMAL",
              "dutyCycle": 0.5,
              "phaseOffsetCycles": 0.0,
              "polarity": "POSITIVE",
              "marginMm": 1.0,
              "annotationSizeMm": 4.0,
              "showAxis": true,
              "axisQuantity": "PITCH_UM",
              "tickCount": 3,
              "showReferenceBands": false,
              "referenceBandSizeMm": 0.0,
              "dpi": 100.0
            }
            """;

    private static final String UNSAFE_RASTER = """
            {
              "widthMm": 210.0,
              "heightMm": 297.0,
              "lineOrientation": "VERTICAL",
              "startPitchUm": 1000.0,
              "endPitchUm": 500.0,
              "progression": "LINEAR_PITCH",
              "progressionDirection": "NORMAL",
              "dutyCycle": 0.5,
              "phaseOffsetCycles": 0.0,
              "polarity": "POSITIVE",
              "marginMm": 5.0,
              "annotationSizeMm": 14.0,
              "showAxis": true,
              "axisQuantity": "PITCH_UM",
              "tickCount": 9,
              "showReferenceBands": false,
              "referenceBandSizeMm": 0.0,
              "dpi": 2400.0
            }
            """;

    @Autowired MockMvc mvc;

    @Test
    void printerProfilesExposeOnlyTrustedCodeOwnedProfiles() throws Exception {
        mvc.perform(get("/api/designs/variable-line-grating/printer-profiles"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(PrinterRasterProfiles.DEFAULT_PROFILE_ID))
                .andExpect(jsonPath("$[0].pageXAxisMapsTo").value("X"))
                .andExpect(jsonPath("$[0].pageYAxisMapsTo").value("Y"))
                .andExpect(jsonPath("$[0].compressionModes").isArray());
    }

    @Test
    void publicInfoAndValidationUseTheSelectedPrinterAxis() throws Exception {
        mvc.perform(post("/api/designs/variable-line-grating/info")
                        .queryParam("printerProfileId", PrinterRasterProfiles.DEFAULT_PROFILE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineOrientation").value("VERTICAL"))
                .andExpect(jsonPath("$.testedDeviceAxis").value("X"))
                .andExpect(jsonPath("$.selectedAxisDpi").value(600.0))
                .andExpect(jsonPath("$.thresholdCrossings").isArray());

        mvc.perform(post("/api/designs/variable-line-grating/validation")
                        .queryParam("printerProfileId", PrinterRasterProfiles.DEFAULT_PROFILE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value("variable-line-grating"))
                .andExpect(jsonPath("$.parameterSnapshot.testedDeviceAxis").value("X"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void boundedPreviewIsPublicAndInline() throws Exception {
        mvc.perform(post("/api/designs/variable-line-grating/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"fresnel-variable-line-grating-preview.png\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G'));
    }

    @Test
    void authenticatedProductionExportsHaveStableMediaAndDisposition() throws Exception {
        mvc.perform(post("/api/designs/variable-line-grating/export.png")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"fresnel-variable-line-grating.png\""));

        MvcResult svg = mvc.perform(post("/api/designs/variable-line-grating/export.svg")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(SVG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"fresnel-variable-line-grating.svg\""))
                .andReturn();
        assertThat(svg.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("orientation=VERTICAL")
                .contains("PRINT 100%");

        mvc.perform(post("/api/designs/variable-line-grating/export.pdf")
                        .with(user("alice").roles("USER"))
                        .queryParam("sheet", "FIT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F'));

        MvcResult pcl = mvc.perform(post("/api/designs/variable-line-grating/export.pcl")
                        .with(user("alice").roles("USER"))
                        .queryParam("printerProfileId", PrinterRasterProfiles.DEFAULT_PROFILE_ID)
                        .queryParam("compression", "TIFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(PCL))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"fresnel-variable-line-grating.pcl\""))
                .andReturn();
        byte[] bytes = pcl.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        assertThat(bytes[0]).isEqualTo((byte) 0x1b);
        assertThat(bytes[1]).isEqualTo((byte) 'E');
    }

    @Test
    void productionExportsRequireAuthentication() throws Exception {
        for (String path : new String[]{"export.png", "export.svg", "export.pdf", "export.pcl"}) {
            mvc.perform(post("/api/designs/variable-line-grating/" + path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void invalidProfileCompressionAndSheetAreRejectedWithoutFallback() throws Exception {
        mvc.perform(post("/api/designs/variable-line-grating/info")
                        .queryParam("printerProfileId", "does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unknown printer raster profile")));

        mvc.perform(post("/api/designs/variable-line-grating/export.pcl")
                        .with(user("alice").roles("USER"))
                        .queryParam("printerProfileId", "does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/designs/variable-line-grating/export.pcl")
                        .with(user("alice").roles("USER"))
                        .queryParam("compression", "ARBITRARY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/designs/variable-line-grating/export.pdf")
                        .with(user("alice").roles("USER"))
                        .queryParam("sheet", "LETTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unknown sheet size")));
    }

    @Test
    void structuralValidationAndRasterResourceLimitsFailSafely() throws Exception {
        mvc.perform(post("/api/designs/variable-line-grating/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"dutyCycle\": 0.5", "\"dutyCycle\": 1.0")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/designs/variable-line-grating/export.png")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNSAFE_RASTER))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("safe")));
    }
}

package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class FresnelJobControllerTest {

    private static final MediaType FRESNEL_JOB =
            MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void savesCanonicalZonePlateJobAsFresnelFile() throws Exception {
        mvc.perform(post("/api/designs/job/save")
                        .contentType(FRESNEL_JOB)
                        .accept(FRESNEL_JOB)
                        .content(zonePlateJob("10.0", "")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FRESNEL_JOB))
                .andExpect(header().string("Content-Disposition",
                        containsString("fresnel-zone-plate.fresnel")))
                .andExpect(jsonPath("$.format").value(FresnelJobDocument.FORMAT_IDENTIFIER))
                .andExpect(jsonPath("$.formatVersion").value(1))
                .andExpect(jsonPath("$.plugin.id").value("zone-plate"))
                .andExpect(jsonPath("$.parameters.maskType").value("BINARY_AMPLITUDE"))
                .andExpect(jsonPath("$.parameters.polarity").value("POSITIVE"))
                .andExpect(jsonPath("$.provenance.parameterSha256", hasLength(64)));
    }

    @Test
    void loadsAndMigratesLegacyDesignDocument() throws Exception {
        String legacy = """
                {
                  "kind": "single",
                  "version": 1,
                  "payload": {
                    "apertureDiameterMm": 10,
                    "focalLengthMm": 200,
                    "wavelengthNm": 550,
                    "dpi": 600
                  }
                }
                """;

        mvc.perform(post("/api/designs/job/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(FRESNEL_JOB)
                        .content(legacy))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FRESNEL_JOB))
                .andExpect(jsonPath("$.plugin.id").value("zone-plate"))
                .andExpect(jsonPath("$.plugin.parameterSchemaVersion").value(1))
                .andExpect(jsonPath("$.parameters.targetOffsetXmm").value(0.0))
                .andExpect(jsonPath("$.parameters.targetOffsetYmm").value(0.0))
                .andExpect(jsonPath("$.parameters.maskType").value("BINARY_AMPLITUDE"));
    }

    @Test
    void parameterHashIsIndependentOfEquivalentNumberSpelling() throws Exception {
        MvcResult integerResult = mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("10", "")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult decimalResult = mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("10.0", "")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode integerJob = mapper.readTree(integerResult.getResponse().getContentAsByteArray());
        JsonNode decimalJob = mapper.readTree(decimalResult.getResponse().getContentAsByteArray());
        assertEquals(
                integerJob.get("provenance").get("parameterSha256").asText(),
                decimalJob.get("provenance").get("parameterSha256").asText());
    }

    @Test
    void rejectsUnknownPlugin() throws Exception {
        String job = zonePlateJob("10", "").replace("zone-plate", "unknown-optic");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("unknown plugin id")));
    }

    @Test
    void rejectsFutureJobVersion() throws Exception {
        String job = zonePlateJob("10", "").replace("\"formatVersion\": 1", "\"formatVersion\": 999");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("newer than supported")));
    }

    @Test
    void rejectsProductionFormatNotSupportedByPlugin() throws Exception {
        String production = """
                ,
                  "production": {
                    "outputs": [
                      { "id": "relief", "format": "stl", "filename": "zone-plate.stl" }
                    ]
                  }
                """;
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("10", production)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("does not support stl export")));
    }

    @Test
    void rejectsOutputFilenameWithPathTraversal() throws Exception {
        String production = """
                ,
                  "production": {
                    "outputs": [
                      { "id": "preview", "format": "png", "filename": "../outside.png" }
                    ]
                  }
                """;
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("10", production)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("without path components")));
    }

    private static String zonePlateJob(String apertureDiameter, String production) {
        return """
                {
                  "$schema": "https://carstenartur.github.io/Fresnel/schemas/fresnel-job-v1.schema.json",
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "zone-plate",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "zone-plate/1"
                  },
                  "parameters": {
                    "apertureDiameterMm": %s,
                    "focalLengthMm": 200.0,
                    "wavelengthNm": 550.0,
                    "dpi": 600.0
                  }%s
                }
                """.formatted(apertureDiameter, production);
    }
}

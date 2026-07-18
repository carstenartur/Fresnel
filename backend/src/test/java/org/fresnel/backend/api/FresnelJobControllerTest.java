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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        String legacy = legacyDocument("single", """
                {
                  "apertureDiameterMm": 10,
                  "focalLengthMm": 200,
                  "wavelengthNm": 550,
                  "dpi": 600
                }
                """);

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
    void migratesEverySupportedLegacyKindToAStablePluginId() throws Exception {
        List<LegacyCase> cases = List.of(
                new LegacyCase("single", "zone-plate", """
                        {"apertureDiameterMm":10,"focalLengthMm":200,"wavelengthNm":550,"dpi":600}
                        """),
                new LegacyCase("hex", "hex-macro-cell", """
                        {"macroRadiusMm":30,"subDiameterMm":10,"subPitchMm":11,
                         "focalLengthMm":1000,"wavelengthNm":550,"dpi":600}
                        """),
                new LegacyCase("foil", "window-foil", """
                        {"sheetWidthMm":200,"sheetHeightMm":100,"macroRadiusMm":25,
                         "subDiameterMm":8,"subPitchMm":9,"wavelengthNm":550,"dpi":150}
                        """),
                new LegacyCase("multifocus", "multi-focus", """
                        {"apertureDiameterMm":10,
                         "focusPoints":[{"xMm":-5,"yMm":0,"zMm":1000}],
                         "wavelengthNm":550,"dpi":1200}
                        """),
                new LegacyCase("rgb", "rgb-zone-plate", """
                        {"base":{"apertureDiameterMm":5,"focalLengthMm":100,
                                 "wavelengthNm":550,"dpi":600},
                         "redNm":630,"greenNm":532,"blueNm":450}
                        """),
                new LegacyCase("hologram", "hologram", """
                        {"targetImageBase64":"AA==","sidePx":16,"iterations":1,"dpi":600}
                        """)
        );

        for (LegacyCase migration : cases) {
            mvc.perform(post("/api/designs/job/load")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(FRESNEL_JOB)
                            .content(legacyDocument(migration.kind(), migration.payload())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plugin.id").value(migration.pluginId()))
                    .andExpect(jsonPath("$.formatVersion").value(1));
        }
    }

    @Test
    void checkedInExampleRoundTripsAndExportsPng() throws Exception {
        byte[] example = readCheckedInExample();
        MvcResult loaded = mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .accept(FRESNEL_JOB)
                        .content(example))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugin.id").value("zone-plate"))
                .andExpect(jsonPath("$.parameters.apertureDiameterMm").value(10.0))
                .andReturn();

        byte[] normalized = loaded.getResponse().getContentAsByteArray();
        mvc.perform(post("/api/designs/job/save")
                        .contentType(FRESNEL_JOB)
                        .accept(FRESNEL_JOB)
                        .content(normalized))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".fresnel")));

        JsonNode job = mapper.readTree(normalized);
        MvcResult png = mvc.perform(post("/api/designs/export.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.IMAGE_PNG)
                        .content(mapper.writeValueAsBytes(job.get("parameters"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andReturn();
        assertTrue(png.getResponse().getContentAsByteArray().length > 100,
                "checked-in example should produce a non-empty PNG");
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
    void rejectsFutureParameterSchemaVersion() throws Exception {
        String job = zonePlateJob("10", "")
                .replace("\"parameterSchemaVersion\": 1", "\"parameterSchemaVersion\": 999");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Parameter schema version 999")));
    }

    @Test
    void rejectsMissingRequiredPluginParameter() throws Exception {
        String job = zonePlateJob("10", "")
                .replace("\"focalLengthMm\": 200.0,", "");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("focalLengthMm")));
    }

    @Test
    void rejectsJobBeforeParsingWhenInputExceedsLimit() throws Exception {
        byte[] oversized = new byte[FresnelJobDocument.MAX_FILE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("exceeds the maximum size")));
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

    @Test
    void rejectsDuplicateProductionOutputFilenamesCaseInsensitively() throws Exception {
        String production = """
                ,
                  "production": {
                    "outputs": [
                      { "id": "one", "format": "png", "filename": "preview.png" },
                      { "id": "two", "format": "png", "filename": "PREVIEW.PNG" }
                    ]
                  }
                """;
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("10", production)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Duplicate production output filename")));
    }

    private static byte[] readCheckedInExample() throws Exception {
        for (Path candidate : List.of(
                Path.of("docs", "jobs", "zone-plate", "on-axis.fresnel"),
                Path.of("..", "docs", "jobs", "zone-plate", "on-axis.fresnel"))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readAllBytes(candidate);
            }
        }
        throw new IllegalStateException("Could not locate docs/jobs/zone-plate/on-axis.fresnel");
    }

    private static String legacyDocument(String kind, String payload) {
        return """
                {
                  "kind": "%s",
                  "version": 1,
                  "payload": %s
                }
                """.formatted(kind, payload);
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

    private record LegacyCase(String kind, String pluginId, String payload) {}
}

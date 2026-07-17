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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class FresnelJobStrictValidationTest {

    private static final MediaType FRESNEL_JOB =
            MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void rejectsUnknownEnvelopeAndParameterFields() throws Exception {
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("""
                                , "rendererClass": "ZonePlateRenderer"
                                """, "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unknown field job.rendererClass")));

        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob("", """
                                , "rendererClass": "ZonePlateRenderer"
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unknown field parameters.rendererClass")));
    }

    @Test
    void requiresExplicitPluginSchemaAndAlgorithmVersions() throws Exception {
        String withoutParameterVersion = zonePlateJob("", "")
                .replace("\"parameterSchemaVersion\": 1,", "");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(withoutParameterVersion))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "plugin.parameterSchemaVersion must be at least 1")));

        String withoutAlgorithmVersion = zonePlateJob("", "")
                .replace(",\n                    \"algorithmVersion\": \"zone-plate/1\"", "");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(withoutAlgorithmVersion))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "plugin.algorithmVersion must not be empty")));
    }

    @Test
    void rejectsUnknownNestedProductionFields() throws Exception {
        String job = zonePlateJob("""
                , "production": {
                    "outputs": [
                      {"format":"png", "filename":"preview.png", "command":"render"}
                    ]
                  }
                """, "");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "Unknown field production.outputs[0].command")));
    }

    @Test
    void rejectsAnEmptyProductionPlanInsteadOfSilentlyDroppingIt() throws Exception {
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob(", \"production\": {}", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "production.outputs must contain at least one output")));

        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob(", \"production\": {\"outputs\": []}", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "production.outputs must contain at least one output")));
    }

    @Test
    void rejectsNullProductionOutputWithItsArrayIndex() throws Exception {
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(zonePlateJob(
                                ", \"production\": {\"outputs\": [null]}", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(
                        "production.outputs[0] must not be null")));
    }

    @Test
    void normalizesDeterministicProductionDefaults() throws Exception {
        String job = zonePlateJob("""
                , "production": {
                    "outputs": [ {"format":"pdf"} ]
                  }
                """, "");
        mvc.perform(post("/api/designs/job/load")
                        .contentType(FRESNEL_JOB)
                        .content(job))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.production.outputs[0].id").value("output-1"))
                .andExpect(jsonPath("$.production.outputs[0].filename")
                        .value("zone-plate-output-1.pdf"))
                .andExpect(jsonPath("$.production.outputs[0].sheet").value("FIT"))
                .andExpect(jsonPath("$.production.outputs[0].printScale").value(1.0));
    }

    @Test
    void canonicalizesDefaultsForEveryNonTrivialLegacyPayload() throws Exception {
        JsonNode hex = loadLegacy("hex", """
                {"macroRadiusMm":30,"subDiameterMm":10,"subPitchMm":11,
                 "focalLengthMm":1000,"wavelengthNm":550,"dpi":600}
                """);
        assertEquals(0.0, hex.at("/parameters/targetOffsetXmm").asDouble());
        assertEquals("BINARY_AMPLITUDE", hex.at("/parameters/maskType").asText());
        assertEquals("POSITIVE", hex.at("/parameters/polarity").asText());

        JsonNode multi = loadLegacy("multifocus", """
                {"apertureDiameterMm":10,
                 "focusPoints":[{"xMm":-5,"yMm":0,"zMm":1000}],
                 "wavelengthNm":550,"dpi":1200}
                """);
        assertEquals("BINARY_AMPLITUDE", multi.at("/parameters/maskType").asText());
        assertEquals("POSITIVE", multi.at("/parameters/polarity").asText());

        JsonNode foil = loadLegacy("foil", """
                {"sheetWidthMm":200,"sheetHeightMm":100,"macroRadiusMm":25,
                 "subDiameterMm":8,"subPitchMm":9,"wavelengthNm":550,"dpi":150}
                """);
        assertEquals("BINARY_AMPLITUDE", foil.at("/parameters/maskType").asText());
        assertFalse(foil.at("/parameters/drawCropMarks").asBoolean());
        assertEquals(0, foil.at("/parameters/cellSpecs").size());

        JsonNode rgb = loadLegacy("rgb", """
                {"base":{"apertureDiameterMm":5,"focalLengthMm":100,
                         "wavelengthNm":550,"dpi":600},
                 "redNm":630,"greenNm":532,"blueNm":450}
                """);
        assertEquals(0.0, rgb.at("/parameters/base/targetOffsetYmm").asDouble());
        assertEquals("BINARY_AMPLITUDE", rgb.at("/parameters/base/maskType").asText());

        JsonNode hologram = loadLegacy("hologram", """
                {"targetImageBase64":"AA==","sidePx":16,"iterations":1,"dpi":600}
                """);
        assertEquals("GREYSCALE_PHASE", hologram.at("/parameters/outputType").asText());
        assertEquals(550.0, hologram.at("/parameters/wavelengthNm").asDouble());
        assertEquals(0.5, hologram.at("/parameters/refractiveIndexDelta").asDouble());
        assertEquals(2.0 * Math.PI,
                hologram.at("/parameters/maxPhaseShiftRad").asDouble(), 1e-12);
    }

    private JsonNode loadLegacy(String kind, String payload) throws Exception {
        MvcResult result = mvc.perform(post("/api/designs/job/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"%s","version":1,"payload":%s}
                                """.formatted(kind, payload)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * @param rootSuffix fields appended after the parameters object
     * @param parameterSuffix fields appended inside the parameters object
     */
    private static String zonePlateJob(String rootSuffix, String parameterSuffix) {
        return """
                {
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "zone-plate",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "zone-plate/1"
                  },
                  "parameters": {
                    "apertureDiameterMm": 10,
                    "focalLengthMm": 200,
                    "wavelengthNm": 550,
                    "dpi": 600%s
                  }%s
                }
                """.formatted(parameterSuffix, rootSuffix);
    }
}

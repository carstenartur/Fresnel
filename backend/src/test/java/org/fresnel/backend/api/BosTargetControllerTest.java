package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BosTargetControllerTest {

    private static final String BASE =
            "/api/measurements/background-oriented-schlieren/target";

    private static final String VALID = """
            {
              "widthPx": 640,
              "heightPx": 480,
              "intendedDpi": 100.0,
              "patternSeed": 20260823,
              "dotDiameterPx": 5,
              "targetFillRatio": 0.10,
              "minimumDotSpacingPx": 2,
              "borderPx": 32,
              "fiducialsEnabled": true,
              "invertPattern": false
            }
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publicManifestIsDeterministicAndContainsReproductionEvidence() throws Exception {
        MvcResult first = mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.algorithmVersion")
                        .value("background-oriented-schlieren-target/1"))
                .andExpect(jsonPath("$.targetId").value(matchesPattern("bos-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.semanticSha256")
                        .value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.widthPx").value(640))
                .andExpect(jsonPath("$.heightPx").value(480))
                .andExpect(jsonPath("$.activeRegion.x").value(32))
                .andExpect(jsonPath("$.activeRegion.y").value(32))
                .andExpect(jsonPath("$.activeRegion.width").value(576))
                .andExpect(jsonPath("$.activeRegion.height").value(416))
                .andExpect(jsonPath("$.fiducials.length()").value(4))
                .andReturn();

        MvcResult second = mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsByteArray());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsByteArray());
        assertThat(firstJson).isEqualTo(secondJson);
        assertThat(firstJson.get("actualFillRatio").doubleValue()).isBetween(0.08, 0.12);
        assertThat(firstJson.get("dotCount").intValue()).isPositive();
    }

    @Test
    void publicPreviewIsInlineChecksummedAndByteDeterministic() throws Exception {
        MvcResult first = mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.ETAG,
                        matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(header().string(BosTargetController.TARGET_SHA256_HEADER,
                        matchesPattern("[0-9a-f]{64}")))
                .andExpect(header().string(BosTargetController.TARGET_ID_HEADER,
                        matchesPattern("bos-[0-9a-f]{12}")))
                .andExpect(header().string(BosTargetController.ACTIVE_REGION_HEADER,
                        "32,32,576,416"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        matchesPattern("inline; filename=\"bos-[0-9a-f]{12}\\.png\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        MvcResult second = mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andReturn();

        byte[] firstBytes = first.getResponse().getContentAsByteArray();
        assertThat(firstBytes).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(second.getResponse().getContentAsByteArray()).isEqualTo(firstBytes);
        assertThat(second.getResponse().getHeader(BosTargetController.TARGET_SHA256_HEADER))
                .isEqualTo(first.getResponse().getHeader(BosTargetController.TARGET_SHA256_HEADER));
    }

    @Test
    void productionDownloadRequiresAuthenticationAndUsesAttachmentDisposition() throws Exception {
        mvc.perform(post(BASE + "/export.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized());

        MvcResult export = mvc.perform(post(BASE + "/export.png")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        matchesPattern("attachment; filename=\"bos-[0-9a-f]{12}\\.png\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertThat(export.getResponse().getContentAsByteArray())
                .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void structuralAndCrossFieldResourceLimitsFailClosed() throws Exception {
        mvc.perform(post(BASE + "/preview.png")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"widthPx\": 640", "\"widthPx\": 319")))
                .andExpect(status().isBadRequest());

        String excessivePixels = VALID
                .replace("\"widthPx\": 640", "\"widthPx\": 5000")
                .replace("\"heightPx\": 480", "\"heightPx\": 5000");
        mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(excessivePixels))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("maximum")));

        String impossibleBorder = VALID.replace("\"borderPx\": 32", "\"borderPx\": 200");
        mvc.perform(post(BASE + "/manifest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(impossibleBorder))
                .andExpect(status().isBadRequest());
    }
}

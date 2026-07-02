package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class AssistantControllerTest {

    @Autowired
    MockMvc mvc;

    /** Reference scenario: 600 dpi, A4, green laser (532 nm), 2 m focus. */
    private static final String REFERENCE_REQUEST = """
            {
              "dpi": 600.0,
              "pageSizeWidthMm": 210.0,
              "pageSizeHeightMm": 297.0,
              "wavelengthNm": 532.0,
              "targetFocusMm": 2000.0
            }
            """;

    @Test
    void referenceScenarioReturnsOkWithRecommendedAndAlternatives() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").exists())
                .andExpect(jsonPath("$.alternatives").isArray())
                .andExpect(jsonPath("$.alternatives", hasSize(2)));
    }

    @Test
    void referenceScenarioRecommendedHasRankOne() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.rank").value(1));
    }

    @Test
    void referenceScenarioAlternativesHaveRanksTwoAndThree() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alternatives[0].rank").value(2))
                .andExpect(jsonPath("$.alternatives[1].rank").value(3));
    }

    @Test
    void referenceScenarioParametersHaveExpectedWavelengthAndFocal() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.parameters.wavelengthNm").value(532.0))
                .andExpect(jsonPath("$.recommended.parameters.focalLengthMm").value(2000.0))
                .andExpect(jsonPath("$.recommended.parameters.dpi").value(600.0));
    }

    @Test
    void referenceScenarioReasonsContainPrintability() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.reasons[*].dimension",
                        hasItem("printability")));
    }

    @Test
    void referenceScenarioGlobalWarningsContainAdvisory() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalWarnings[*].code", hasItem("ADVISORY")));
    }

    @Test
    void referenceScenarioValidationMetricsArePresent() throws Exception {
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.validation.metrics").exists())
                .andExpect(jsonPath("$.recommended.validation.metrics.pixelsPerOuterZone").isNumber());
    }

    @Test
    void maxApertureCapIsRespected() throws Exception {
        String cappedRequest = """
                {
                  "dpi": 600.0,
                  "pageSizeWidthMm": 210.0,
                  "pageSizeHeightMm": 297.0,
                  "wavelengthNm": 532.0,
                  "targetFocusMm": 2000.0,
                  "maxApertureMm": 6.0
                }
                """;
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cappedRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.parameters.apertureDiameterMm").value(lessThanOrEqualTo(6.0)))
                .andExpect(jsonPath("$.alternatives[0].parameters.apertureDiameterMm").value(lessThanOrEqualTo(6.0)))
                .andExpect(jsonPath("$.alternatives[1].parameters.apertureDiameterMm").value(lessThanOrEqualTo(6.0)));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        String incomplete = """
                {
                  "dpi": 600.0,
                  "pageSizeWidthMm": 210.0,
                  "pageSizeHeightMm": 297.0,
                  "wavelengthNm": 532.0
                }
                """;
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incomplete))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void negativeDpiReturns400() throws Exception {
        String bad = """
                {
                  "dpi": -600.0,
                  "pageSizeWidthMm": 210.0,
                  "pageSizeHeightMm": 297.0,
                  "wavelengthNm": 532.0,
                  "targetFocusMm": 2000.0
                }
                """;
        mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rankingIsDeterministicAcrossTwoCalls() throws Exception {
        String body1 = mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andReturn().getResponse().getContentAsString();

        String body2 = mvc.perform(post("/api/assistant/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFERENCE_REQUEST))
                .andReturn().getResponse().getContentAsString();

        assertEquals(body1, body2, "Two identical requests must produce identical responses");
    }
}

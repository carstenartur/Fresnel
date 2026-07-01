package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ComparisonControllerTest {

    @Autowired MockMvc mvc;

    // Two single zone-plate variants that differ only in focal length.
    private static final String TWO_SINGLE_VARIANTS = """
            {
              "variants": [
                {
                  "label": "Short focal",
                  "pluginId": "single",
                  "singleParams": {
                    "apertureDiameterMm": 20.0,
                    "focalLengthMm": 1000.0,
                    "wavelengthNm": 550.0,
                    "dpi": 1200.0
                  }
                },
                {
                  "label": "Long focal",
                  "pluginId": "single",
                  "singleParams": {
                    "apertureDiameterMm": 20.0,
                    "focalLengthMm": 5000.0,
                    "wavelengthNm": 550.0,
                    "dpi": 1200.0
                  }
                }
              ],
              "rank": false
            }
            """;

    @Test
    void compareReturnsTwoVariantResults() throws Exception {
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].label").value("Short focal"))
                .andExpect(jsonPath("$.variants[1].label").value("Long focal"));
    }

    @Test
    void compareIncludesValidationReports() throws Exception {
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].validation.metrics").exists())
                .andExpect(jsonPath("$.variants[0].validation.qualityReport").exists())
                .andExpect(jsonPath("$.variants[1].validation.metrics").exists())
                .andExpect(jsonPath("$.variants[1].validation.qualityReport").exists());
    }

    @Test
    void compareIncludesBase64Previews() throws Exception {
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].previewBase64").isString())
                .andExpect(jsonPath("$.variants[0].previewWidthPx").isNumber())
                .andExpect(jsonPath("$.variants[0].previewHeightPx").isNumber())
                .andExpect(jsonPath("$.variants[0].pixelsPerMm").isNumber());
    }

    @Test
    void compareListsParameterDifferences() throws Exception {
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                // focalLengthMm differs between the two variants.
                .andExpect(jsonPath("$.parameterDifferences[?(@.parameter=='focalLengthMm')]").exists())
                .andExpect(jsonPath(
                        "$.parameterDifferences[?(@.parameter=='focalLengthMm')].values[0]",
                        hasItem("1000")))
                .andExpect(jsonPath(
                        "$.parameterDifferences[?(@.parameter=='focalLengthMm')].values[0]",
                        hasItem("5000")));
    }

    @Test
    void compareWithRankingAttachesScores() throws Exception {
        String body = TWO_SINGLE_VARIANTS.replace("\"rank\": false", "\"rank\": true");
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].score.rank").isNumber())
                .andExpect(jsonPath("$.variants[0].score.score").isNumber())
                .andExpect(jsonPath("$.variants[0].score.explanation").isString())
                .andExpect(jsonPath("$.variants[1].score.rank").isNumber());
    }

    @Test
    void compareRankingIsDeterministic() throws Exception {
        // Variant 0 (f=1000, NA=0.01): higher optical quality outweighs worse printability.
        // Composite: 0.4*(1.3/6.5) + 0.3*(0.01/0.01) + 0.3*(recip0/recip0) ≈ 0.68
        // Variant 1 (f=5000, NA=0.002): better printability but lower NA + larger DoF.
        // Composite: 0.4*(6.5/6.5) + 0.3*(0.002/0.01) + 0.3*(recip1/recip0) ≈ 0.47
        // → variant 0 ranks 1st.
        String body = TWO_SINGLE_VARIANTS.replace("\"rank\": false", "\"rank\": true");
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].score.rank").value(1))
                .andExpect(jsonPath("$.variants[1].score.rank").value(2));
    }

    @Test
    void compareRejectsFewerThanTwoVariants() throws Exception {
        String body = """
                {
                  "variants": [
                    {
                      "label": "Only one",
                      "pluginId": "single",
                      "singleParams": {
                        "apertureDiameterMm": 10.0,
                        "focalLengthMm": 500.0,
                        "wavelengthNm": 550.0,
                        "dpi": 1200.0
                      }
                    }
                  ],
                  "rank": false
                }
                """;
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compareRejectsMissingLabel() throws Exception {
        String body = """
                {
                  "variants": [
                    {
                      "pluginId": "single",
                      "singleParams": {
                        "apertureDiameterMm": 10.0,
                        "focalLengthMm": 500.0,
                        "wavelengthNm": 550.0,
                        "dpi": 1200.0
                      }
                    },
                    {
                      "label": "B",
                      "pluginId": "single",
                      "singleParams": {
                        "apertureDiameterMm": 10.0,
                        "focalLengthMm": 1000.0,
                        "wavelengthNm": 550.0,
                        "dpi": 1200.0
                      }
                    }
                  ],
                  "rank": false
                }
                """;
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compareSupportsRgbVariant() throws Exception {
        String body = """
                {
                  "variants": [
                    {
                      "label": "Zone plate",
                      "pluginId": "single",
                      "singleParams": {
                        "apertureDiameterMm": 5.0,
                        "focalLengthMm": 100.0,
                        "wavelengthNm": 550.0,
                        "dpi": 600.0
                      }
                    },
                    {
                      "label": "RGB zone plate",
                      "pluginId": "rgb",
                      "rgbParams": {
                        "base": {
                          "apertureDiameterMm": 5.0,
                          "focalLengthMm": 100.0,
                          "wavelengthNm": 550.0,
                          "dpi": 600.0
                        },
                        "redNm": 630.0,
                        "greenNm": 532.0,
                        "blueNm": 450.0
                      }
                    }
                  ],
                  "rank": false
                }
                """;
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].pluginId").value("single"))
                .andExpect(jsonPath("$.variants[1].pluginId").value("rgb"));
    }

    @Test
    void comparePreviewsShareSameScale() throws Exception {
        // Both variants use dpi=1200; pixels-per-mm = 1200/25.4 ≈ 47.24.
        // The comparison endpoint must report the same scale for all variants.
        double expectedPixPerMm = 1200.0 / 25.4;
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].pixelsPerMm",
                        closeTo(expectedPixPerMm, 0.01)))
                .andExpect(jsonPath("$.variants[1].pixelsPerMm",
                        closeTo(expectedPixPerMm, 0.01)));
    }

    @Test
    void compareParameterDifferencesAreSortedAlphabetically() throws Exception {
        mvc.perform(post("/api/designs/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_SINGLE_VARIANTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterDifferences[0].parameter").isString())
                .andExpect(jsonPath("$.parameterDifferences[1].parameter").isString());
    }
}

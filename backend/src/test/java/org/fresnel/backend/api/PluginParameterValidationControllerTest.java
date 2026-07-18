package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PluginParameterValidationControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void validZonePlateParametersUseTheCanonicalJobNormalizer() throws Exception {
        mvc.perform(post("/api/plugins/zone-plate/parameters/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 10,
                                  "focalLengthMm": 1000,
                                  "wavelengthNm": 550,
                                  "dpi": 1200
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value("zone-plate"))
                .andExpect(jsonPath("$.parameterSchemaVersion").value(1))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.normalizedParameters.targetOffsetXmm").value(0.0))
                .andExpect(jsonPath("$.normalizedParameters.targetOffsetYmm").value(0.0))
                .andExpect(jsonPath("$.normalizedParameters.maskType").value("BINARY_AMPLITUDE"))
                .andExpect(jsonPath("$.normalizedParameters.polarity").value("POSITIVE"));
    }

    @Test
    void beanConstraintFailuresReturnStableFieldPaths() throws Exception {
        mvc.perform(post("/api/plugins/zone-plate/parameters/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 0,
                                  "focalLengthMm": 1000,
                                  "wavelengthNm": 550,
                                  "dpi": 1200
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.normalizedParameters").doesNotExist())
                .andExpect(jsonPath("$.errors[*].path", hasItem("apertureDiameterMm")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("CONSTRAINT_VIOLATION")));
    }

    @Test
    void unknownFieldsAreRejectedWithoutBecomingExecutableMetadata() throws Exception {
        mvc.perform(post("/api/plugins/zone-plate/parameters/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 10,
                                  "focalLengthMm": 1000,
                                  "wavelengthNm": 550,
                                  "dpi": 1200,
                                  "rendererClass": "example.Attacker"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path").value("rendererClass"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
    }

    @Test
    void nestedCollectionViolationsRetainTheirArrayIndex() throws Exception {
        mvc.perform(post("/api/plugins/multi-focus/parameters/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 10,
                                  "focusPoints": [{"xMm": 0, "yMm": 0, "zMm": 0}],
                                  "wavelengthNm": 550,
                                  "dpi": 1200
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[*].path", hasItem("focusPoints[0].zMm")));
    }

    @Test
    void unknownPluginReturns404() throws Exception {
        mvc.perform(post("/api/plugins/not-a-plugin/parameters/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}

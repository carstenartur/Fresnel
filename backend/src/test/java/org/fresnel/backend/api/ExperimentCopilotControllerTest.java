package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ExperimentCopilotControllerTest {

    @Autowired MockMvc mvc;

    @Test
    @WithAnonymousUser
    void exposesProviderStatusWithoutCredentials() throws Exception {
        mvc.perform(get("/api/assistant/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mock')].available", hasItem(true)));
    }

    @Test
    @WithAnonymousUser
    void proposalEndpointRequiresAuthenticationEvenForMockProvider() throws Exception {
        mvc.perform(post("/api/assistant/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "mock",
                                  "request": "Create a 532 nm zone plate with a 1 m focus."
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesOfflineMockProviderWithoutRequiringApiQuota() throws Exception {
        mvc.perform(get("/api/assistant/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mock')].available", hasItem(true)));
    }

    @Test
    void naturalLanguageBecomesValidatedReproducibleJob() throws Exception {
        mvc.perform(post("/api/assistant/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "mock",
                                  "request": "Create a printable 532 nm zone plate with a one metre focus at 1200 DPI. Prefer a robust design that is easy to fabricate."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.selectedPluginId").value("zone-plate"))
                .andExpect(jsonPath("$.normalizedParameters.wavelengthNm").value(532.0))
                .andExpect(jsonPath("$.normalizedParameters.focalLengthMm").value(1000.0))
                .andExpect(jsonPath("$.normalizedParameters.dpi").value(1200.0))
                .andExpect(jsonPath("$.validation.pluginId").value("zone-plate"))
                .andExpect(jsonPath("$.job.format").value(FresnelJobDocument.FORMAT_IDENTIFIER))
                .andExpect(jsonPath("$.job.provenance.createdWith").value("Fresnel experiment copilot (mock)"))
                .andExpect(jsonPath("$.job.provenance.parameterSha256", not(blankOrNullString())))
                .andExpect(jsonPath("$.parameters[?(@.path == 'apertureDiameterMm')].source",
                        hasItem("COPILOT_INFERRED")));
    }

    @Test
    void missingCoreIntentProducesClarificationInsteadOfFabricatedJob() throws Exception {
        mvc.perform(post("/api/assistant/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "mock",
                                  "request": "Create a robust printable zone plate at 1200 DPI."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.unresolvedQuestions.length()").value(2))
                .andExpect(jsonPath("$.job").doesNotExist())
                .andExpect(jsonPath("$.validation").doesNotExist());
    }

    @Test
    void unknownProviderIsRejectedAsClientError() throws Exception {
        mvc.perform(post("/api/assistant/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "shell-executor",
                                  "request": "Create a 532 nm zone plate with a 1 m focus."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROPOSAL"));
    }
}

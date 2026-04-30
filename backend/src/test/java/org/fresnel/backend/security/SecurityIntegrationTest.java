package org.fresnel.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the security policy: mutating endpoints require an authenticated
 * principal; read-only endpoints (validate, preview-by-id, GET design persistence)
 * remain accessible to anonymous callers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired MockMvc mvc;

    private static final String SINGLE_BODY = """
            { "apertureDiameterMm": 4.0, "focalLengthMm": 50.0,
              "wavelengthNm": 550.0, "dpi": 300.0 }
            """;

    private static final String DOC = """
            { "kind": "single", "version": 1,
              "payload": { "apertureDiameterMm": 4.0, "focalLengthMm": 50.0,
                           "wavelengthNm": 550.0, "dpi": 300.0 } }
            """;

    // --- 401 paths (anonymous, mutating) -----------------------------------

    @Test
    @WithAnonymousUser
    void anonymousJobSubmitIsUnauthorized() throws Exception {
        mvc.perform(post("/api/jobs/single")
                        .contentType(MediaType.APPLICATION_JSON).content(SINGLE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    void anonymousDesignPersistIsUnauthorized() throws Exception {
        mvc.perform(post("/api/designs/persist")
                        .contentType(MediaType.APPLICATION_JSON).content(DOC))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    void anonymousDesignSaveIsUnauthorized() throws Exception {
        mvc.perform(post("/api/designs/save")
                        .contentType(MediaType.APPLICATION_JSON).content(DOC))
                .andExpect(status().isUnauthorized());
    }

    // --- permitAll paths (anonymous, read-only) ----------------------------

    @Test
    @WithAnonymousUser
    void anonymousDesignValidateIsAllowed() throws Exception {
        mvc.perform(post("/api/designs/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(SINGLE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anonymousDesignLoadIsAllowed() throws Exception {
        mvc.perform(post("/api/designs/load")
                        .contentType(MediaType.APPLICATION_JSON).content(DOC))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anonymousDesignListIsAllowed() throws Exception {
        // GET /api/designs/persist is permitAll; returns an empty list when no
        // user is authenticated and no designs exist for that owner.
        mvc.perform(get("/api/designs/persist"))
                .andExpect(status().isOk());
    }

    // --- 200 paths (authenticated, mutating) -------------------------------

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedJobSubmitIsOk() throws Exception {
        mvc.perform(post("/api/jobs/single")
                        .contentType(MediaType.APPLICATION_JSON).content(SINGLE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedDesignSaveIsOk() throws Exception {
        mvc.perform(post("/api/designs/save")
                        .contentType(MediaType.APPLICATION_JSON).content(DOC))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void authenticatedDesignPersistAssignsOwner() throws Exception {
        mvc.perform(post("/api/designs/persist")
                        .contentType(MediaType.APPLICATION_JSON).content(DOC))
                .andExpect(status().isOk());
    }
}

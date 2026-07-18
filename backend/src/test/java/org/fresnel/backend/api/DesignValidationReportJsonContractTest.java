package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class DesignValidationReportJsonContractTest {

    @Autowired MockMvc mvc;

    @Test
    void warningOnlyPluginReportSerializesValidTrue() throws Exception {
        mvc.perform(post("/api/designs/multi-focus/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 6.0,
                                  "focusPoints": [
                                    {"xMm": -3.0, "yMm": 0.0, "zMm": 600.0},
                                    {"xMm": 3.0, "yMm": 0.0, "zMm": 600.0}
                                  ],
                                  "wavelengthNm": 550.0,
                                  "dpi": 600.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void reportWithErrorFindingSerializesValidFalse() throws Exception {
        mvc.perform(post("/api/designs/zone-plate/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apertureDiameterMm": 20.0,
                                  "focalLengthMm": 100.0,
                                  "wavelengthNm": 550.0,
                                  "dpi": 2400.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }
}

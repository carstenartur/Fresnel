package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "fresnel.capture.mock.enabled=false")
@AutoConfigureMockMvc
class CaptureProviderStatusControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void overviewTruthfullyReportsNoConfiguredProvider() throws Exception {
        mvc.perform(get("/api/capture-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.messageCode").value("NO_CAPTURE_PROVIDER_CONFIGURED"))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.providers").isEmpty());
    }

    @Test
    void unknownProviderReturns404() throws Exception {
        mvc.perform(get("/api/capture-providers/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}

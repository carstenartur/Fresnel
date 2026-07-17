package org.fresnel.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SpaControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void stablePluginRoutesForwardToTheBundledSpa() throws Exception {
        mvc.perform(get("/plugins/hex-macro-cell"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/plugins/zone-plate"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    voidPluginApiRoutesRemainApiResponses() throws Exception {
        mvc.perform(get("/api/plugins/hex-macro-cell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("hex-macro-cell"));
    }
}

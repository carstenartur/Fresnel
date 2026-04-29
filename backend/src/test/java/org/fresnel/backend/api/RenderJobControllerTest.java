package org.fresnel.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RenderJobControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void submitSinglePollAndDownload() throws Exception {
        String body = """
                {
                  "apertureDiameterMm": 4.0,
                  "focalLengthMm": 50.0,
                  "wavelengthNm": 550.0,
                  "dpi": 300.0
                }
                """;
        MvcResult res = mvc.perform(post("/api/jobs/single")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();
        JsonNode root = json.readTree(res.getResponse().getContentAsString());
        String jobId = root.get("jobId").asText();
        assertNotNull(jobId);

        // Poll until the job completes (small render so completes quickly).
        String state = "QUEUED";
        long until = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < until) {
            MvcResult st = mvc.perform(get("/api/jobs/{id}", jobId))
                    .andExpect(status().isOk())
                    .andReturn();
            state = json.readTree(st.getResponse().getContentAsString()).get("state").asText();
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) break;
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", state);

        // Download result.
        mvc.perform(get("/api/jobs/{id}/result.png", jobId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/api/jobs/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/result.png", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}

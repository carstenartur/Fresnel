package org.fresnel.backend.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class DesignDocumentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void saveAndLoadRoundtripsSingleDesign() throws Exception {
        String doc = """
                {
                  "kind": "single",
                  "version": 1,
                  "payload": {
                    "apertureDiameterMm": 10.0,
                    "focalLengthMm": 200.0,
                    "wavelengthNm": 550.0,
                    "dpi": 600.0
                  }
                }
                """;
        MvcResult saved = mvc.perform(post("/api/designs/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(doc))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("fresnel-design-single.json")))
                .andReturn();
        // The saved bytes are themselves a valid load payload.
        String savedJson = saved.getResponse().getContentAsString();
        MvcResult loaded = mvc.perform(post("/api/designs/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(savedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("single"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.payload.apertureDiameterMm").value(10.0))
                .andReturn();
        JsonNode node = mapper.readTree(loaded.getResponse().getContentAsString());
        assertNotNull(node.get("payload"));
        assertEquals(550.0, node.get("payload").get("wavelengthNm").asDouble(), 1e-9);
    }

    @Test
    void rejectsUnknownKind() throws Exception {
        String doc = """
                {
                  "kind": "telescope",
                  "version": 1,
                  "payload": { "x": 1 }
                }
                """;
        mvc.perform(post("/api/designs/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(doc))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFutureSchemaOnLoad() throws Exception {
        String doc = """
                {
                  "kind": "single",
                  "version": 999,
                  "payload": { "apertureDiameterMm": 10.0 }
                }
                """;
        mvc.perform(post("/api/designs/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(doc))
                .andExpect(status().isBadRequest());
    }
}

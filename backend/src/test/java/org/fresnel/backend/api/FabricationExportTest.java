package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FabricationExportTest {

    private static final String SINGLE_BODY = """
            {
              "apertureDiameterMm": 5.0,
              "focalLengthMm": 50.0,
              "wavelengthNm": 550.0,
              "dpi": 600.0
            }
            """;

    @Autowired MockMvc mvc;

    @Test
    void exportDxfReturnsDxfText() throws Exception {
        MvcResult res = mvc.perform(post("/api/designs/export.dxf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SINGLE_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/dxf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("fresnel-zone-plate.dxf")))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.startsWith("0\nSECTION\n2\nENTITIES\n"));
        assertTrue(body.endsWith("0\nENDSEC\n0\nEOF\n"));
        assertTrue(body.contains("CIRCLE"));
    }

    @Test
    void exportGerberReturnsRs274xText() throws Exception {
        MvcResult res = mvc.perform(post("/api/designs/export.gbr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SINGLE_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.gerber"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("fresnel-zone-plate.gbr")))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("%FSLAX46Y46*%"));
        assertTrue(body.contains("%MOMM*%"));
        assertTrue(body.endsWith("M02*\n"));
    }

    @Test
    void exportDxfRejectsInvalidPayload() throws Exception {
        mvc.perform(post("/api/designs/export.dxf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"apertureDiameterMm\": -1.0 }"))
                .andExpect(status().isBadRequest());
    }
}

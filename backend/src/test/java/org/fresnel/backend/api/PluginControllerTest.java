package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PluginControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listPluginsReturnsAllSixPlugins() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(6)));
    }

    @Test
    void listPluginsContainsExpectedIds() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems(
                        "zone-plate",
                        "rgb-zone-plate",
                        "multi-focus",
                        "hex-macro-cell",
                        "window-foil",
                        "hologram"
                )));
    }

    @Test
    void listPluginsIncludesRequiredFields() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].displayName").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].rendererClass").exists())
                .andExpect(jsonPath("$[0].parameterType").exists())
                .andExpect(jsonPath("$[0].frontendModeId").exists())
                .andExpect(jsonPath("$[0].stability").exists())
                .andExpect(jsonPath("$[0].capabilities").isArray())
                .andExpect(jsonPath("$[0].propagationModes").isArray());
    }

    @Test
    void getPluginByIdReturnsZonePlate() throws Exception {
        mvc.perform(get("/api/plugins/zone-plate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("zone-plate"))
                .andExpect(jsonPath("$.displayName").value("Zone Plate"))
                .andExpect(jsonPath("$.rendererClass").value("ZonePlateRenderer"))
                .andExpect(jsonPath("$.parameterType").value("SingleZonePlateParameters"))
                .andExpect(jsonPath("$.frontendModeId").value("single"))
                .andExpect(jsonPath("$.stability").value("STABLE"));
    }

    @Test
    void getPluginByIdReturnsZonePlateCapabilities() throws Exception {
        mvc.perform(get("/api/plugins/zone-plate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities", hasItems(
                        "EXPORT_PNG", "EXPORT_SVG", "EXPORT_PDF",
                        "EXPORT_DXF", "EXPORT_GERBER",
                        "PREVIEW_PNG", "PROPAGATION_PREVIEW",
                        "PRINTABILITY_ANALYSIS", "OPTICAL_QUALITY_REPORT"
                )))
                .andExpect(jsonPath("$.propagationModes", hasItems("FRESNEL_TF", "FRAUNHOFER")))
                .andExpect(jsonPath("$.supportsPrintabilityAnalysis").value(true))
                .andExpect(jsonPath("$.supportsOpticalQualityReport").value(true))
                .andExpect(jsonPath("$.supportsPropagationPreview").value(true))
                .andExpect(jsonPath("$.supportsExperimentalValidation").value(false));
    }

    @Test
    void getPluginByIdReturnsHologram() throws Exception {
        mvc.perform(get("/api/plugins/hologram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("hologram"))
                .andExpect(jsonPath("$.rendererClass").value("HologramSynthesizer"))
                .andExpect(jsonPath("$.capabilities", hasItems("EXPORT_PNG", "EXPORT_STL")))
                .andExpect(jsonPath("$.propagationModes", empty()));
    }

    @Test
    void getPluginByUnknownIdReturns404() throws Exception {
        mvc.perform(get("/api/plugins/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}

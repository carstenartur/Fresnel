package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PluginControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void listPluginsReturnsAllSixPluginsInRegistryOrder() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].id").value("zone-plate"))
                .andExpect(jsonPath("$[1].id").value("hex-macro-cell"))
                .andExpect(jsonPath("$[2].id").value("window-foil"))
                .andExpect(jsonPath("$[3].id").value("multi-focus"))
                .andExpect(jsonPath("$[4].id").value("rgb-zone-plate"))
                .andExpect(jsonPath("$[5].id").value("hologram"));
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
    void listPluginsIncludesSchemaMetadataWithoutInternalPathsOrModeAliases() throws Exception {
        mvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].displayName").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].rendererClass").exists())
                .andExpect(jsonPath("$[0].parameterType").exists())
                .andExpect(jsonPath("$[0].frontendModeId").doesNotExist())
                .andExpect(jsonPath("$[0].stability").exists())
                .andExpect(jsonPath("$[0].capabilities").isArray())
                .andExpect(jsonPath("$[0].propagationModes").isArray())
                .andExpect(jsonPath("$[0].parameterSchemaVersion").value(1))
                .andExpect(jsonPath("$[0].editorMode").exists())
                .andExpect(jsonPath("$[0].schemaUrl").value("/api/plugins/zone-plate/schema"))
                .andExpect(jsonPath("$[0].parameterSchemaResource").doesNotExist())
                .andExpect(jsonPath("$[0].uiSchemaResource").doesNotExist());
    }

    @Test
    void getPluginByIdReturnsZonePlate() throws Exception {
        mvc.perform(get("/api/plugins/zone-plate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("zone-plate"))
                .andExpect(jsonPath("$.displayName").value("Zone Plate"))
                .andExpect(jsonPath("$.rendererClass").value("ZonePlateRenderer"))
                .andExpect(jsonPath("$.parameterType").value("SingleZonePlateParameters"))
                .andExpect(jsonPath("$.frontendModeId").doesNotExist())
                .andExpect(jsonPath("$.stability").value("STABLE"))
                .andExpect(jsonPath("$.parameterSchemaVersion").value(1))
                .andExpect(jsonPath("$.editorMode").value("SCHEMA_WITH_EXTENSIONS"));
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
    void schemaEndpointReturnsDeterministicZonePlateContract() throws Exception {
        MvcResult first = mvc.perform(get("/api/plugins/zone-plate/schema"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pluginId").value("zone-plate"))
                .andExpect(jsonPath("$.parameterSchemaVersion").value(1))
                .andExpect(jsonPath("$.editorMode").value("SCHEMA_WITH_EXTENSIONS"))
                .andExpect(jsonPath("$.parameterSchema.$schema")
                        .value("https://json-schema.org/draft/2020-12/schema"))
                .andExpect(jsonPath("$.parameterSchema.additionalProperties").value(false))
                .andExpect(jsonPath("$.parameterSchema.properties.apertureDiameterMm.x-fresnel-unit")
                        .value("mm"))
                .andExpect(jsonPath("$.uiSchema.groups[0].id").value("geometry"))
                .andExpect(jsonPath("$.uiSchema.widgets.dpi.type").value("number-with-presets"))
                .andExpect(jsonPath("$.defaults.apertureDiameterMm").value(10.0))
                .andExpect(jsonPath("$.capabilities").isArray())
                .andReturn();

        MvcResult second = mvc.perform(get("/api/plugins/zone-plate/schema"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString());
    }

    @Test
    void unknownPluginAndSchemaReturn404() throws Exception {
        mvc.perform(get("/api/plugins/does-not-exist"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/does-not-exist/schema"))
                .andExpect(status().isNotFound());
    }
}

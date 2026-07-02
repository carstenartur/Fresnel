package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ExperimentControllerTest {

    @Autowired MockMvc mvc;

    private static final String RECORD = """
            {
              "designId": "demo-design-001",
              "pluginId": "zone-plate",
              "parameterHash": "zp-demo-hash",
              "designDocument": {
                "kind": "single",
                "version": 1,
                "payload": {
                  "apertureDiameterMm": 10.0,
                  "focalLengthMm": 1000.0,
                  "wavelengthNm": 550.0,
                  "dpi": 1200.0
                }
              },
              "validationReport": {
                "pluginId": "zone-plate",
                "parameterHash": "zp-demo-hash",
                "parameterSnapshot": {
                  "apertureDiameterMm": "10",
                  "focalLengthMm": "1000",
                  "wavelengthNm": "550",
                  "dpi": "1200"
                },
                "wavelengthMinNm": 550.0,
                "wavelengthMaxNm": 550.0,
                "apertureDiameterMm": 10.0,
                "targetFocalDistancesMm": [1000.0],
                "pixelSizeMicrons": 21.167,
                "assumptions": [],
                "metrics": [],
                "findings": []
              },
              "setup": {
                "printerModel": "Epson EcoTank ET-1810",
                "nominalDpi": 1200.0,
                "effectiveDpi": 1140.0,
                "materialType": "Transparent PET foil",
                "exposureSettings": "Matte photo / high quality",
                "lightSourceType": "Green LED",
                "wavelengthNm": 532.0,
                "spectrumEstimate": "narrow-band LED",
                "environmentalNotes": "Bench test at room temperature",
                "photoReferences": ["focus-setup.jpg", "spot-closeup.jpg"]
              },
              "measurement": {
                "targetFocalLengthMm": 1000.0,
                "measuredFoci": [
                  {
                    "label": "Primary focus",
                    "measuredFocalLengthMm": 1025.0,
                    "measuredSpotSizeMicrons": 180.0,
                    "focusRating": "Sharp center, faint halo",
                    "notes": "Peak intensity located by camera rail"
                  }
                ]
              }
            }
            """;

    @Test
    void compareComputesFocalLengthErrorPercent() throws Exception {
        mvc.perform(post("/api/experiments/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparison.targetFocalLengthMm").value(1000.0))
                .andExpect(jsonPath("$.comparison.measuredFocalLengthMm").value(1025.0))
                .andExpect(jsonPath("$.comparison.focalLengthErrorMm").value(25.0))
                .andExpect(jsonPath("$.comparison.focalLengthErrorPercent",
                        closeTo(2.5, 1e-9)))
                .andExpect(jsonPath("$.comparison.summary",
                        containsString("Measured focal length 1025.000 mm vs target 1000.000 mm")));
    }

    @Test
    void exportJsonReturnsAttachment() throws Exception {
        mvc.perform(post("/api/experiments/export.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("fresnel-experiment-zone-plate.json")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.setup.photoReferences[0]").value("focus-setup.jpg"));
    }

    @Test
    void exportMarkdownIncludesComparisonSummary() throws Exception {
        mvc.perform(post("/api/experiments/export.md")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("fresnel-experiment-zone-plate.md")))
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().string(containsString("# Experimental validation record")))
                .andExpect(content().string(containsString("Measured focal length: 1025 mm")))
                .andExpect(content().string(containsString("Focal-length error (%): 2.5 %")));
    }

    @Test
    void compareRejectsMismatchedParameterHash() throws Exception {
        mvc.perform(post("/api/experiments/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD.replaceFirst("\"parameterHash\": \"zp-demo-hash\"",
                                "\"parameterHash\": \"other-hash\"")))
                .andExpect(status().isBadRequest());
    }
}

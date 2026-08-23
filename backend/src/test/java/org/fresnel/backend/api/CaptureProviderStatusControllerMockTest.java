package org.fresnel.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "fresnel.capture.mock.enabled=true")
@AutoConfigureMockMvc
class CaptureProviderStatusControllerMockTest {

    @Autowired MockMvc mvc;

    @Test
    void overviewPublishesConnectedProviderAndRedactedDeviceCapabilities() throws Exception {
        mvc.perform(get("/api/capture-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONNECTED"))
                .andExpect(jsonPath("$.messageCode").value("ALL_CAPTURE_PROVIDERS_CONNECTED"))
                .andExpect(jsonPath("$.providers.length()").value(1))
                .andExpect(jsonPath("$.providers[0].id").value("mock-capture"))
                .andExpect(jsonPath("$.providers[0].displayName").value("Mock camera service"))
                .andExpect(jsonPath("$.providers[0].state").value("CONNECTED"))
                .andExpect(jsonPath("$.providers[0].messageCode").value("MOCK_CONNECTED"))
                .andExpect(jsonPath("$.providers[0].protocolVersion").value(1))
                .andExpect(jsonPath("$.providers[0].minimumSupportedProtocolVersion").value(1))
                .andExpect(jsonPath("$.providers[0].capabilities", hasItems(
                        "STILL_CAPTURE", "EXTERNAL_STEP_TRIGGER", "SHA256_INTEGRITY")))
                .andExpect(jsonPath("$.providers[0].supportedFormats", hasItems("PNG")))
                .andExpect(jsonPath("$.providers[0].limits.maximumSteps").value(64))
                .andExpect(jsonPath("$.providers[0].devices.length()").value(1))
                .andExpect(jsonPath("$.providers[0].devices[0].id").value("mock-camera"))
                .andExpect(jsonPath("$.providers[0].devices[0].state").value("CONNECTED"))
                .andExpect(jsonPath("$.providers[0].url").doesNotExist())
                .andExpect(jsonPath("$.providers[0].token").doesNotExist())
                .andExpect(jsonPath("$.providers[0].devices[0].serialNumber").doesNotExist())
                .andExpect(jsonPath("$.providers[0].devices[0].filePath").doesNotExist());
    }

    @Test
    void providerDetailUsesSamePublicShape() throws Exception {
        mvc.perform(get("/api/capture-providers/mock-capture"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value("mock-capture"))
                .andExpect(jsonPath("$.state").value("CONNECTED"))
                .andExpect(jsonPath("$.devices[0].displayName")
                        .value("Deterministic mock camera"));
    }
}

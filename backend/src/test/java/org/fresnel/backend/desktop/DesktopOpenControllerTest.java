package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "fresnel.desktop.enabled=true",
        "fresnel.desktop.instance-secret=0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
        "fresnel.desktop.open-token-ttl-seconds=300"
})
@AutoConfigureMockMvc
class DesktopOpenControllerTest {

    private static final String SECRET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
    private static final MediaType FRESNEL_JOB =
            MediaType.parseMediaType(FresnelJobDocument.MEDIA_TYPE);

    @Autowired MockMvc mvc;

    @Test
    void authenticatedPrimaryHandshakeAcceptsAndConsumesAJobExactlyOnce() throws Exception {
        mvc.perform(get("/api/internal/desktop/ping")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().string(DesktopOpenController.PING_BODY))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

        MvcResult opened = mvc.perform(post("/api/internal/desktop/open")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(FRESNEL_JOB)
                        .content(validJob()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn();
        String token = opened.getResponse().getContentAsString().trim();

        mvc.perform(get("/api/desktop/open/{token}", token).with(loopback()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.job.plugin.id").value("zone-plate"))
                .andExpect(jsonPath("$.job.parameters.focalLengthMm").value(1000.0))
                .andExpect(content().string(not(containsString("sourcePath"))));

        mvc.perform(get("/api/desktop/open/{token}", token).with(loopback()))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingWrongAndNonLoopbackAuthenticationAreRejected() throws Exception {
        mvc.perform(get("/api/internal/desktop/ping").with(loopback()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/desktop/ping")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/desktop/ping")
                        .with(remote("192.0.2.10"))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidJobIsReturnedAsANonFatalBrowserResult() throws Exception {
        MvcResult opened = mvc.perform(post("/api/internal/desktop/open")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(FRESNEL_JOB)
                        .content("{not-json"))
                .andExpect(status().isAccepted())
                .andReturn();

        String token = opened.getResponse().getContentAsString().trim();
        mvc.perform(get("/api/desktop/open/{token}", token).with(loopback()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.job").doesNotExist())
                .andExpect(jsonPath("$.errorCode").value("INVALID_JOB"))
                .andExpect(jsonPath("$.errorMessage", containsString("Invalid Fresnel job JSON")));
    }

    @Test
    void emptyAndOversizedBodiesAreRejectedBeforeQueueing() throws Exception {
        mvc.perform(post("/api/internal/desktop/open")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(FRESNEL_JOB)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());

        byte[] oversized = new byte[FresnelJobDocument.MAX_FILE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        mvc.perform(post("/api/internal/desktop/open")
                        .with(loopback())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(FRESNEL_JOB)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void publicConsumeEndpointIsAlsoLoopbackOnlyAndRejectsMalformedTokens() throws Exception {
        mvc.perform(get("/api/desktop/open/not-valid").with(loopback()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/desktop/open/{token}",
                        "A".repeat(43)).with(remote("198.51.100.5")))
                .andExpect(status().isForbidden());
    }

    private static String bearer() {
        return "Bearer " + SECRET;
    }

    private static RequestPostProcessor loopback() {
        return remote("127.0.0.1");
    }

    private static RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static byte[] validJob() {
        return """
                {
                  "format": "io.github.carstenartur.fresnel.job",
                  "formatVersion": 1,
                  "plugin": {
                    "id": "zone-plate",
                    "parameterSchemaVersion": 1,
                    "algorithmVersion": "zone-plate/1"
                  },
                  "parameters": {
                    "apertureDiameterMm": 10,
                    "focalLengthMm": 1000,
                    "wavelengthNm": 550,
                    "dpi": 1200
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }
}

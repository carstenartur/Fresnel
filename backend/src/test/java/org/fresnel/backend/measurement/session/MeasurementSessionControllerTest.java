package org.fresnel.backend.measurement.session;

import org.fresnel.backend.security.MockMvcSecurityConfig;
import org.fresnel.backend.security.SecurityConfig;
import org.fresnel.backend.security.SecurityCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MeasurementSessionController.class)
@Import({MeasurementSessions.class, MeasurementBundles.class, SecurityConfig.class, SecurityCredentials.class,
        MockMvcSecurityConfig.class, MeasurementSessionControllerTest.TestSecurity.class})
class MeasurementSessionControllerTest {
    @TestConfiguration(proxyBeanMethods = false) @EnableWebSecurity static class TestSecurity {}
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("fresnel.measurements.directory", () -> directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    static final String ROOT = "/api/measurement-sessions";
    static final String PLAN = """
            {"pluginId":"background-oriented-schlieren","parameters":{"seed":2},"steps":["reference","disturbed"]}
            """;

    @Test void anonymousCannotReadWriteOrImport() throws Exception {
        for (var req : List.of(get(ROOT), post(ROOT), post(ROOT + "/import"), put(ROOT + "/x/assets/reference"),
                delete(ROOT + "/x"), get(ROOT + "/x/bundle"))) mvc.perform(req).andExpect(status().isUnauthorized());
    }

    @Test void privateReadSupportsRangeAndConditionalRequests() throws Exception {
        var alice = user("alice");
        var result = mvc.perform(post(ROOT).with(alice).contentType("application/json").content(PLAN))
                .andExpect(status().isOk()).andReturn();
        var id = json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString();
        var path = ROOT + "/" + id + "/assets/reference";
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] bytes = out.toByteArray();
        String hash = MeasurementSessions.hash(bytes);
        mvc.perform(put(path).with(alice).contentType("image/png").header("X-Content-SHA256", hash).content(bytes))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CAPTURING"));
        mvc.perform(get(path).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(alice).header("Range", "bytes=0-3")).andExpect(status().isPartialContent())
                .andExpect(content().bytes(java.util.Arrays.copyOf(bytes, 4)));
        mvc.perform(get(path).with(alice).header("If-None-Match", "\"" + hash + "\"")).andExpect(status().isNotModified());
        mvc.perform(get(ROOT + "/" + id + "/bundle").with(alice)).andExpect(status().isOk())
                .andExpect(content().contentType(MeasurementBundles.TYPE));
        mvc.perform(delete(ROOT + "/" + id).with(alice)).andExpect(status().isNoContent());
        mvc.perform(get(ROOT + "/" + id).with(alice)).andExpect(status().isNotFound());
    }
}

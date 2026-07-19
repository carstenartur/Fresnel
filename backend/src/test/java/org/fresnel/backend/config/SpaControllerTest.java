package org.fresnel.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaTypeFactory;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

        mvc.perform(get("/plugins/zone-plate/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/assistant"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        mvc.perform(get("/compare/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void bundledJavaScriptAndCssRemainStaticResources() throws Exception {
        assertStaticResource(
                "/assets/fresnel-static-test.js",
                "window.__FRESNEL_STATIC_TEST__ = true;\n");
        assertStaticResource(
                "/assets/fresnel-static-test.css",
                ":root { --fresnel-static-test: 1; }\n");
    }

    @Test
    void pluginApiRoutesRemainApiResponses() throws Exception {
        mvc.perform(get("/api/plugins/hex-macro-cell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("hex-macro-cell"));
    }

    private void assertStaticResource(String path, String expectedBody) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(handler().handlerType(ResourceHttpRequestHandler.class))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaTypeFactory.getMediaType(path).orElseThrow()))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        not(containsString("text/html"))))
                .andExpect(content().string(expectedBody));
    }
}

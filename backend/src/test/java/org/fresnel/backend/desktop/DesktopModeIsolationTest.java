package org.fresnel.backend.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DesktopModeIsolationTest {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @Test
    void ordinaryServerContextDoesNotCreateDesktopBeans() {
        assertTrue(context.getBeansOfType(DesktopOpenQueue.class).isEmpty());
        assertTrue(context.getBeansOfType(DesktopOpenController.class).isEmpty());
    }

    @Test
    void ordinaryServerContextDoesNotExposeDesktopTokenEndpoints() throws Exception {
        mvc.perform(get("/api/desktop/open/{token}", "A".repeat(43)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/internal/desktop/ping"))
                .andExpect(status().isNotFound());
    }
}

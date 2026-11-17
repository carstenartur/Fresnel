package org.fresnel.backend.measurement.photographer;

import org.fresnel.backend.security.SecurityConfig;
import org.fresnel.backend.security.MockMvcSecurityConfig;
import org.fresnel.backend.security.SecurityCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PhotographerPairingController.class, properties = {
        "fresnel.capture.photographer.enabled=true",
        "fresnel.security.admin.password=pair-admin-test", "fresnel.security.user.password=pair-user-test"
})
@Import({SecurityConfig.class, SecurityCredentials.class, MockMvcSecurityConfig.class, PhotographerPairingAuthorizationTest.TestSecurity.class})
class PhotographerPairingAuthorizationTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurity {}

    private static final String PATH = "/api/admin/capture-providers/photographer/pairing";
    @Autowired MockMvc mvc;
    @MockitoBean PhotographerPairing pairing;

    @Test
    void onlyConfiguredAdministratorsMayReadOrChangePairings() throws Exception {
        for (var request : List.of(get(PATH), post(PATH), delete(PATH))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.with(httpBasic("user", "pair-user-test"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(pairing);
    }

    @Test
    void adminCanInspectPairAndForgetWithoutReceivingAServiceToken() throws Exception {
        var auth = httpBasic("admin", "pair-admin-test");
        when(pairing.status()).thenReturn(new PhotographerPairing.Status(false, true, null, null, List.of()));
        mvc.perform(get(PATH).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.paired").value(false))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(post(PATH).with(auth).contentType("application/json").content("{\"pairingCode\":\"one-use\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(pairing).pair("one-use");
        mvc.perform(delete(PATH).with(auth)).andExpect(status().isNoContent());
        verify(pairing).forget();
    }
}

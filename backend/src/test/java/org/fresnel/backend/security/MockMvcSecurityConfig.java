package org.fresnel.backend.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

/**
 * Bridges Spring Security and MockMvc in tests.
 *
 * <p>Spring Boot 3.x shipped a dedicated {@code MockMvcSecurityAutoConfiguration} that
 * applied {@link SecurityMockMvcConfigurers#springSecurity()} to every MockMvc instance
 * created via {@code @AutoConfigureMockMvc}. That auto-config was removed in Spring
 * Boot 4 (the {@code spring-boot-webmvc-test} module no longer ships a security
 * integration), which means {@link org.springframework.security.test.context.support.WithMockUser}
 * and friends would otherwise be silently ignored — every authenticated request is
 * rejected with 401 because the security filter chain reads its context from a
 * {@link org.springframework.security.web.context.SecurityContextRepository}, not from
 * {@code SecurityContextHolder}.
 *
 * <p>This class lives in {@code src/test/java} so the test-only dependency on
 * {@code spring-security-test} is acceptable, and is in the {@code
 * org.fresnel.backend.*} package tree so Spring Boot's component scan picks it up
 * automatically for every {@code @SpringBootTest}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({MockMvcBuilderCustomizer.class, SecurityMockMvcConfigurers.class})
public class MockMvcSecurityConfig {

    @Bean
    public MockMvcBuilderCustomizer springSecurityMockMvcBuilderCustomizer() {
        return builder -> {
            if (builder instanceof ConfigurableMockMvcBuilder<?> configurable) {
                configurable.apply(SecurityMockMvcConfigurers.springSecurity());
            }
        };
    }
}

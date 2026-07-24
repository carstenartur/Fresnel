package org.fresnel.backend.security;

import org.fresnel.backend.FresnelBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkSecurityProfileStartupTest {

    @Test
    void containerProfileFailsStartupWithBlankCredentials() {
        assertThatThrownBy(() -> startContainer("", ""))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("FRESNEL_SECURITY_USER_PASSWORD");
    }

    @Test
    void containerProfileStartsWithExplicitStrongCredentials() {
        try (ConfigurableApplicationContext context = startContainer(
                "correct-horse-battery-staple",
                "violet-meteor-archive-2026")) {
            assertThat(context.isActive()).isTrue();
            SecurityCredentials credentials = context.getBean(SecurityCredentials.class);
            assertThat(credentials.explicitCredentialsRequired()).isTrue();
        }
    }

    private static ConfigurableApplicationContext startContainer(
            String userPassword,
            String adminPassword) {
        String database = "jdbc:h2:mem:credential-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        return new SpringApplicationBuilder(FresnelBackendApplication.class)
                .profiles("container")
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + database,
                        "--fresnel.security.user.password=" + userPassword,
                        "--fresnel.security.admin.password=" + adminPassword);
    }
}

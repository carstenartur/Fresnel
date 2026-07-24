package org.fresnel.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityCredentialsTest {

    @Test
    void localProfileMayUseDocumentedDevelopmentAccounts() {
        SecurityCredentials credentials = new SecurityCredentials(
                "user", "user", "admin", "admin", false);

        assertThat(credentials.userName()).isEqualTo("user");
        assertThat(credentials.adminName()).isEqualTo("admin");
        assertThat(credentials.explicitCredentialsRequired()).isFalse();
    }

    @Test
    void everyProfileRejectsMissingOrBlankValues() {
        assertThatThrownBy(() -> new SecurityCredentials(
                "user", null, "admin", "strong-admin-password", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fresnel.security.user.password");
        assertThatThrownBy(() -> new SecurityCredentials(
                "user", "   ", "admin", "strong-admin-password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fresnel.security.user.password");
    }

    @Test
    void networkProfileRejectsPublishedAndWeakCredentials() {
        assertThatThrownBy(() -> new SecurityCredentials(
                "user", "user", "admin", "another-strong-password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FRESNEL_SECURITY_USER_PASSWORD");
        assertThatThrownBy(() -> new SecurityCredentials(
                "alice", "too-short", "root", "another-strong-password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12");
        assertThatThrownBy(() -> new SecurityCredentials(
                "alice", "same-strong-password", "root", "same-strong-password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void networkProfileAcceptsDistinctExplicitCredentials() {
        SecurityCredentials credentials = new SecurityCredentials(
                "alice", "correct-horse-battery-staple",
                "root", "violet-meteor-archive-2026", true);

        assertThat(credentials.userName()).isEqualTo("alice");
        assertThat(credentials.adminName()).isEqualTo("root");
        assertThat(credentials.explicitCredentialsRequired()).isTrue();
    }
}

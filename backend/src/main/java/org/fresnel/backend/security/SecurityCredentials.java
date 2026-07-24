package org.fresnel.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validated application accounts used by HTTP Basic authentication.
 *
 * <p>Local and loopback-only profiles may retain the documented development
 * accounts. Network-exposed profiles set {@code require-explicit-credentials}
 * and then fail closed unless two distinct, non-default passwords are supplied.
 * This keeps an omitted environment variable from silently enabling a known
 * credential on a container or PostgreSQL deployment.</p>
 */
@Component
public final class SecurityCredentials {

    private static final int MIN_NETWORK_PASSWORD_LENGTH = 12;
    private static final Set<String> PUBLISHED_DEFAULT_PASSWORDS = Set.of(
            "user", "admin", "password", "changeme");

    private final String userName;
    private final String userPassword;
    private final String adminName;
    private final String adminPassword;
    private final boolean explicitCredentialsRequired;

    public SecurityCredentials(
            @Value("${fresnel.security.user.username:user}") String userName,
            @Value("${fresnel.security.user.password:user}") String userPassword,
            @Value("${fresnel.security.admin.username:admin}") String adminName,
            @Value("${fresnel.security.admin.password:admin}") String adminPassword,
            @Value("${fresnel.security.require-explicit-credentials:false}")
            boolean explicitCredentialsRequired) {
        this.userName = requireText(userName, "fresnel.security.user.username");
        this.userPassword = requireText(userPassword, "fresnel.security.user.password");
        this.adminName = requireText(adminName, "fresnel.security.admin.username");
        this.adminPassword = requireText(adminPassword, "fresnel.security.admin.password");
        this.explicitCredentialsRequired = explicitCredentialsRequired;

        if (this.userName.equals(this.adminName)) {
            throw new IllegalStateException("User and administrator usernames must be different");
        }
        if (explicitCredentialsRequired) {
            requireNetworkPassword(this.userName, this.userPassword,
                    "FRESNEL_SECURITY_USER_PASSWORD");
            requireNetworkPassword(this.adminName, this.adminPassword,
                    "FRESNEL_SECURITY_ADMIN_PASSWORD");
            if (this.userPassword.equals(this.adminPassword)) {
                throw new IllegalStateException(
                        "User and administrator passwords must be different in a network profile");
            }
        }
    }

    public String userName() { return userName; }
    public String userPassword() { return userPassword; }
    public String adminName() { return adminName; }
    public String adminPassword() { return adminPassword; }
    public boolean explicitCredentialsRequired() { return explicitCredentialsRequired; }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be blank");
        }
        return value;
    }

    private static void requireNetworkPassword(
            String username,
            String password,
            String environmentVariable) {
        String normalized = password.toLowerCase(Locale.ROOT);
        if (password.length() < MIN_NETWORK_PASSWORD_LENGTH) {
            throw new IllegalStateException(environmentVariable
                    + " must contain at least " + MIN_NETWORK_PASSWORD_LENGTH + " characters");
        }
        if (password.equalsIgnoreCase(username)
                || PUBLISHED_DEFAULT_PASSWORDS.contains(normalized)) {
            throw new IllegalStateException(environmentVariable
                    + " must not use a published/default credential");
        }
    }
}

package org.fresnel.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP-Basic security configuration for the Fresnel backend.
 *
 * <p>Public, read-only endpoints (validate, preview, info, GET design persistence
 * by id) remain accessible to unauthenticated callers so the SPA stays usable
 * without login. All mutating endpoints (POST design save/persist, POST job
 * submissions, hologram submit, DELETE) require an authenticated principal.
 *
 * <p>Two users are seeded from configuration ({@code fresnel.security.user.*} and
 * {@code fresnel.security.admin.*}) — these are placeholder credentials suitable
 * for local development; for any non-throwaway environment override the
 * passwords via environment variables (see README).
 *
 * <p>Sessions are stateless (no {@code JSESSIONID}). CSRF is disabled for the
 * {@code /api/**} surface because authentication is conveyed by the {@code
 * Authorization} header on every request, not by a cookie that an attacker could
 * piggy-back via a forged form.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        // Public read-only endpoints.
                        .requestMatchers(HttpMethod.GET,
                                "/api/designs/persist/**",
                                "/api/designs/preview*",
                                "/api/designs/*/info",
                                "/api/jobs/*",
                                "/api/jobs/*/events",
                                "/api/jobs/*/result.png",
                                "/error", "/", "/index.html",
                                "/assets/**", "/static/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/designs/validate",
                                "/api/designs/preview.png",
                                "/api/designs/load",
                                "/api/designs/*/info",
                                "/api/designs/*/preview.png").permitAll()
                        // Mutating endpoints require an authenticated user.
                        .requestMatchers(HttpMethod.POST,
                                "/api/designs/save",
                                "/api/designs/persist",
                                "/api/designs/export*",
                                "/api/designs/*/export*",
                                "/api/jobs/**",
                                "/api/holograms/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> {});
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(
            PasswordEncoder encoder,
            @Value("${fresnel.security.user.username:user}") String userName,
            @Value("${fresnel.security.user.password:user}") String userPassword,
            @Value("${fresnel.security.admin.username:admin}") String adminName,
            @Value("${fresnel.security.admin.password:admin}") String adminPassword) {
        UserDetails user = User.withUsername(userName)
                .password(encoder.encode(userPassword))
                .roles("USER")
                .build();
        UserDetails admin = User.withUsername(adminName)
                .password(encoder.encode(adminPassword))
                .roles("USER", "ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}

package org.fresnel.backend.security;

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
 * <p>Public analytical endpoints remain accessible without login. Mutating,
 * export and render-job lifecycle endpoints require an authenticated principal.
 * Render-job reads are private because they can expose user-generated images,
 * progress details and persisted job metadata.</p>
 *
 * <p>Accounts are supplied by {@link SecurityCredentials}. Local profiles may
 * use the documented development credentials, while container/PostgreSQL
 * profiles enable fail-closed explicit-credential validation.</p>
 *
 * <p>Sessions are stateless (no {@code JSESSIONID}). CSRF is disabled for the
 * {@code /api/**} surface because authentication is conveyed by the
 * {@code Authorization} header on every request, not by a browser session
 * cookie.</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        // A render-job identifier is not a public share token. Every
                        // submit/status/SSE/result request requires authentication;
                        // owner-or-admin authorization is enforced by the job service.
                        .requestMatchers("/api/jobs/**").authenticated()
                        // Public read-only endpoints.
                        .requestMatchers(HttpMethod.GET,
                                "/api/designs/persist/**",
                                "/api/designs/preview*",
                                "/api/designs/*/info",
                                "/api/assistant/providers",
                                "/error", "/", "/index.html",
                                "/assets/**", "/static/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/designs/validate",
                                "/api/designs/preview.png",
                                "/api/designs/load",
                                "/api/designs/*/info",
                                "/api/designs/*/preview.png",
                                "/api/assistant/recommend").permitAll()
                        // Endpoints that mutate state or may consume configured quota.
                        .requestMatchers(HttpMethod.POST,
                                "/api/assistant/propose",
                                "/api/designs/save",
                                "/api/designs/persist",
                                "/api/designs/export*",
                                "/api/designs/*/export*",
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
            SecurityCredentials credentials) {
        UserDetails user = User.withUsername(credentials.userName())
                .password(encoder.encode(credentials.userPassword()))
                .roles("USER")
                .build();
        UserDetails admin = User.withUsername(credentials.adminName())
                .password(encoder.encode(credentials.adminPassword()))
                .roles("USER", "ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}

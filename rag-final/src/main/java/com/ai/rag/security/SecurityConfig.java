package com.ai.rag.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.ai.rag.repository.UserRepository;

/**
 * Security Configuration — Production-Grade (Spring Security 6)
 *
 * KEY FIXES vs. the original:
 *
 * ══════════════════════════════════════════════════════════════════════════
 * BUG #1 (ROOT CAUSE of all 500 errors):
 *   BEFORE: .requestMatchers("/api/**").permitAll()
 *   AFTER:  .requestMatchers("/api/**").authenticated()
 *
 *   Why it caused NPE: permitAll() lets unauthenticated requests reach
 *   the controller. The JWT filter ran but set no authentication (expired
 *   token → exception swallowed → chain.doFilter() → controller). Then
 *   @AuthenticationPrincipal UserDetails was null →
 *   resolveUser(null) → null.getUsername() → NullPointerException → 500.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * BUG #2: No AuthenticationEntryPoint configured.
 *   Default behavior redirects to /login — wrong for a REST API.
 *   Fixed: JwtAuthenticationEntryPoint returns JSON 401.
 *
 * BUG #3: No AccessDeniedHandler configured.
 *   Default behavior redirects to /403 — wrong for a REST API.
 *   Fixed: JwtAccessDeniedHandler returns JSON 403.
 *
 * IMPROVEMENT: @EnableMethodSecurity added to support @PreAuthorize
 * annotations on service/controller methods for fine-grained access control.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // enables @PreAuthorize / @PostAuthorize on methods
@Slf4j
public class SecurityConfig {

    // @Lazy breaks the circular dependency:
    // SecurityConfig → JwtAuthenticationFilter → UserDetailsService → (defined here) → cycle.
    // @Lazy injects a proxy; the real bean is resolved when filterChain() is first used.
    private final JwtAuthenticationFilter    jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAccessDeniedHandler     accessDeniedHandler;
    private final UserRepository             userRepository;

    public SecurityConfig(
            @Lazy JwtAuthenticationFilter    jwtFilter,
            JwtAuthenticationEntryPoint      entryPoint,
            JwtAccessDeniedHandler           accessDeniedHandler,
            UserRepository                   userRepository) {
        this.jwtFilter          = jwtFilter;
        this.entryPoint         = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.userRepository     = userRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — stateless JWT API, no browser session to protect
                .csrf(AbstractHttpConfigurer::disable)

                // Stateless — no HttpSession created or used
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Custom handlers for 401 / 403 (JSON responses, not HTML redirects) ──
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)       // 401 → JSON
                        .accessDeniedHandler(accessDeniedHandler)   // 403 → JSON
                )

                // ── Authorization rules ────────────────────────────────────────────────
                .authorizeHttpRequests(auth -> auth

                        // Public — Swagger UI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // Public — Auth endpoints (login, signup, refresh)
                        .requestMatchers("/auth/**").permitAll()

                        // Public — basic health & info probes (for load balancers / k8s)
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // Protected — all other actuator endpoints (metrics, env, beans, etc.)
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // ── FIX #1: /api/** MUST be authenticated ──────────────────────────
                        // BEFORE: .requestMatchers("/api/**").permitAll()  ← THE ROOT BUG
                        // Unauthenticated requests reached controllers with null UserDetails
                        // → NullPointerException → HTTP 500.
                        .requestMatchers("/api/**").authenticated()

                        // Everything else also requires authentication
                        .anyRequest().authenticated()
                )

                // Register the JWT filter before Spring's username/password filter
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ── Beans ─────────────────────────────────────────────────────────────────

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 12 — good balance of security vs. login latency (~300ms on modern hardware)
        return new BCryptPasswordEncoder(12);
    }
}
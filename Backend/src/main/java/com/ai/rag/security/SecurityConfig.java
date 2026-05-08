package com.ai.rag.security;

import jakarta.servlet.DispatcherType;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.ai.rag.repository.UserRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

    private final JwtAuthenticationFilter     jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAccessDeniedHandler      accessDeniedHandler;
    private final UserRepository              userRepository;

    public SecurityConfig(
            @Lazy JwtAuthenticationFilter    jwtFilter,
            JwtAuthenticationEntryPoint      entryPoint,
            JwtAccessDeniedHandler           accessDeniedHandler,
            UserRepository                   userRepository) {
        this.jwtFilter           = jwtFilter;
        this.entryPoint          = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.userRepository      = userRepository;

        // MODE_INHERITABLETHREADLOCAL: propagates SecurityContext from parent
        // thread to child threads (Reactor boundedElastic, async dispatch threads).
        // Combined with DelegatingSecurityContextAsyncTaskExecutor in WebMvcAsyncConfig,
        // this gives full SecurityContext coverage across all async boundaries.
        SecurityContextHolder.setStrategyName(
                SecurityContextHolder.MODE_INHERITABLETHREADLOCAL
        );
        log.info("[Security] Using InheritableThreadLocal SecurityContext strategy");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth

                        // ── CRITICAL FIX ───────────────────────────────────────────────
                        // ASYNC and ERROR dispatcher types are internal Tomcat operations,
                        // NOT real user requests. They never carry an Authorization header.
                        //
                        // Without this, Spring Security re-runs AuthorizationFilter on the
                        // async dispatch that finalizes SSE streams. It sees no JWT on that
                        // internal dispatch → AccessDeniedException → response already
                        // committed → ERR_INCOMPLETE_CHUNKED_ENCODING in the browser.
                        //
                        // This MUST be the first rule — Spring Security evaluates rules
                        // in order and stops at the first match.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()

                        // Swagger UI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // Auth endpoints — public
                        .requestMatchers("/auth/**").permitAll()

                        // Health/info — public for load balancers
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // Other actuator endpoints — admin only
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // All /api/** requires a valid JWT
                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

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
        return new BCryptPasswordEncoder(12);
    }
}
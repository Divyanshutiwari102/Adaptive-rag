package com.ai.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the RAG backend.
 *
 * WHY THIS IS NEEDED FOR STREAMING:
 *
 * The SSE streaming endpoint (POST /api/rag/ask/stream) has additional CORS
 * requirements compared to regular JSON endpoints:
 *
 *   1. exposedHeaders must include "Content-Type" and "Cache-Control"
 *      The browser reads these headers to determine the stream encoding.
 *      Without exposedHeaders, the browser can only access a small whitelist
 *      of "CORS-safelisted" response headers — Content-Type with non-standard
 *      values (text/event-stream) is blocked by some browsers.
 *
 *   2. allowCredentials(true) is required when the frontend reads the
 *      Authorization header pattern and uses credentials mode.
 *      NOTE: When allowCredentials is true, allowedOrigins cannot be "*".
 *      Must list exact origins. We list localhost:3000 for development.
 *
 *   3. maxAge(3600) caches the preflight OPTIONS response for 1 hour.
 *      Without this, the browser sends an OPTIONS preflight before EVERY
 *      POST to /api/rag/ask/stream, adding latency to every chat message.
 *
 * IMPORTANT: This WebMvcConfigurer is read by Spring Security's .cors()
 * setup in SecurityConfig. If you add more origins (e.g., production domain),
 * add them here — no SecurityConfig change needed.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        // Development origins — add your production domain here
                        .allowedOrigins(
                                "http://localhost:3000",
                                "http://127.0.0.1:3000"
                        )
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                        // Allow all request headers including Authorization (JWT)
                        .allowedHeaders("*")
                        // Expose response headers the browser JavaScript can read.
                        // Content-Type is needed for SSE (text/event-stream).
                        // Cache-Control is needed for SSE no-cache directive.
                        // Authorization is exposed for token refresh flows.
                        .exposedHeaders(
                                "Authorization",
                                "Content-Type",
                                "Cache-Control",
                                "X-Request-ID"
                        )
                        // Required for fetch() with credentials mode.
                        // Cannot be combined with wildcard allowedOrigins.
                        .allowCredentials(true)
                        // Cache the preflight response for 1 hour
                        .maxAge(3600);
            }
        };
    }
}
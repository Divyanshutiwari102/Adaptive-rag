package com.ai.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JWT Authentication Filter — Production-Grade
 *
 * KEY FIXES vs. the original:
 *
 * 1. shouldNotFilter(): skips the filter entirely for public paths (/auth/**, swagger, actuator
 *    health). Although permitAll() in SecurityConfig also allows these, bypassing the filter
 *    avoids unnecessary DB lookups and logs noise on every login/signup call.
 *
 * 2. ExpiredJwtException / SignatureException are now caught SEPARATELY and return an
 *    immediate HTTP 401 JSON response. The original code's generic catch + chain.doFilter()
 *    let the request fall through with null authentication → NPE in controllers.
 *
 * 3. SecurityContextHolder.clearContext() is called on any JWT error to ensure no stale
 *    authentication leaks across requests in the same thread.
 *
 * 4. UsernameNotFoundException (user deleted after token issued) is handled explicitly —
 *    returns 401, not 500.
 *
 * 5. ObjectMapper writes a consistent JSON error body, matching GlobalExceptionHandler's
 *    ErrorResponse shape so clients see the same structure for all error cases.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil            jwtUtil;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper       objectMapper;

    // ── Paths that are always public — filter short-circuits here ────────────
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/auth/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Skip the JWT filter entirely for public paths.
     * Spring Security's permitAll() already allows them, but shouldNotFilter()
     * prevents unnecessary processing (no DB lookup, no log noise).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Authorization header → continue without authentication.
        // Spring Security will enforce access rules downstream.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);

        // ── Specific exception types handled in order of expected frequency ──
        try {
            String username = jwtUtil.extractUsername(jwt);   // throws if token is bad

            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtUtil.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    log.debug("[JWT] Authenticated user '{}' for {}", username, request.getServletPath());
                }
                // If isTokenValid() returns false (shouldn't happen after extractUsername succeeds,
                // but be defensive) → fall through with no auth set; Security enforces access rules.
            }
            chain.doFilter(request, response);

        } catch (ExpiredJwtException ex) {
            // Token is structurally valid but past its expiry date.
            log.warn("[JWT] Token expired for request {}: {}", request.getServletPath(), ex.getMessage());
            SecurityContextHolder.clearContext();
            sendUnauthorized(response, "Token has expired. Please log in again.");

        } catch (SignatureException ex) {
            // Token signature does not match — tampered or wrong secret.
            log.warn("[JWT] Invalid signature for request {}: {}", request.getServletPath(), ex.getMessage());
            SecurityContextHolder.clearContext();
            sendUnauthorized(response, "Invalid token signature.");

        } catch (MalformedJwtException ex) {
            // Token is not a valid JWT at all (bad format, missing dots, etc.).
            log.warn("[JWT] Malformed token for request {}: {}", request.getServletPath(), ex.getMessage());
            SecurityContextHolder.clearContext();
            sendUnauthorized(response, "Malformed token.");

        } catch (UsernameNotFoundException ex) {
            // Token was valid when issued but the user has since been deleted.
            log.warn("[JWT] User not found for request {}: {}", request.getServletPath(), ex.getMessage());
            SecurityContextHolder.clearContext();
            sendUnauthorized(response, "User account no longer exists.");

        } catch (Exception ex) {
            // Catch-all for unexpected JWT library exceptions.
            log.error("[JWT] Unexpected filter error for request {}: {}", request.getServletPath(), ex.getMessage(), ex);
            SecurityContextHolder.clearContext();
            sendUnauthorized(response, "Authentication failed.");
        }
    }

    // ── Helper: write a JSON 401 body and stop the filter chain ──────────────

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // Matches the shape of GlobalExceptionHandler.ErrorResponse
        Map<String, Object> body = Map.of(
                "status",    401,
                "error",     "Unauthorized",
                "message",   message,
                "timestamp", LocalDateTime.now().toString()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
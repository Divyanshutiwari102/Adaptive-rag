package com.ai.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Invoked by Spring Security when an unauthenticated request reaches a
 * protected endpoint and NO JWT filter has already handled the response.
 *
 * Without this bean, Spring Security's default entry point redirects to
 * /login — completely wrong for a stateless REST API. This class returns
 * a JSON 401 that matches GlobalExceptionHandler.ErrorResponse.
 *
 * Wired in SecurityConfig via:
 *   http.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest  request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.warn("[Security] Unauthenticated access attempt: {} {}", request.getMethod(), request.getServletPath());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = Map.of(
                "status",    401,
                "error",     "Unauthorized",
                "message",   "Authentication required. Please provide a valid Bearer token.",
                "timestamp", LocalDateTime.now().toString()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
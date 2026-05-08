package com.ai.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Invoked by Spring Security when an AUTHENTICATED user tries to access a
 * resource they don't have permission for (e.g., a USER hitting /actuator/**
 * which requires ADMIN role).
 *
 * Without this, Spring Security redirects to /403 or returns an empty body.
 * This class returns a consistent JSON 403 matching GlobalExceptionHandler.ErrorResponse.
 *
 * Wired in SecurityConfig via:
 *   http.exceptionHandling(e -> e.accessDeniedHandler(accessDeniedHandler))
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest  request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        log.warn("[Security] Access denied for '{}' on {} {}",
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "unknown",
                request.getMethod(), request.getServletPath());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = Map.of(
                "status",    403,
                "error",     "Forbidden",
                "message",   "You don't have permission to access this resource.",
                "timestamp", LocalDateTime.now().toString()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
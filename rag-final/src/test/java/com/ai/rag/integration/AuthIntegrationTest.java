package com.ai.rag.integration;

import com.ai.rag.dto.LoginRequest;
import com.ai.rag.dto.SignupRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for /auth endpoints.
 * Uses real PostgreSQL (Testcontainers) + full Spring context.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper     objectMapper;

    @Test
    @DisplayName("POST /auth/signup returns 200 with JWT token")
    void signup_returnsJwt() {
        SignupRequest req = new SignupRequest("Test User", "itest_" + System.nanoTime() + "@example.com", "SecurePass123!");

        ResponseEntity<String> resp = restTemplate.postForEntity("/auth/signup", req, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("accessToken");
    }

    @Test
    @DisplayName("POST /auth/signup with duplicate email returns 409")
    void signup_duplicateEmail_returns409() {
        String email = "duplicate_" + System.nanoTime() + "@example.com";
        SignupRequest req = new SignupRequest("User A", email, "Password1!");

        restTemplate.postForEntity("/auth/signup", req, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity("/auth/signup", req, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /auth/login with valid credentials returns JWT")
    void login_validCredentials_returnsJwt() {
        String email = "login_" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/auth/signup", new SignupRequest("Login User", email, "Pass123!"), String.class);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/login", new LoginRequest(email, "Pass123!"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("accessToken");
    }

    @Test
    @DisplayName("POST /auth/login with wrong password returns 401")
    void login_wrongPassword_returns401() {
        String email = "wrongpass_" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/auth/signup", new SignupRequest("U", email, "RealPass1!"), String.class);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/login", new LoginRequest(email, "WrongPass!"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/rag/ask without token returns 403")
    void ask_withoutToken_returns403() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/rag/history", String.class);
        assertThat(resp.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }
}

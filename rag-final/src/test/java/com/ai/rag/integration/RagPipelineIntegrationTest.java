package com.ai.rag.integration;

import com.ai.rag.dto.AskRequest;
import com.ai.rag.dto.SignupRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import com.ai.rag.service.OpenAiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for the /api/rag/ask endpoint.
 *
 * Uses real PostgreSQL (Testcontainers), full Spring Boot context.
 * OpenAiClient is mocked — no real API calls, no flakiness, no cost.
 *
 * This tests the full HTTP → Security → RateLimiter → RagPipeline → DB stack.
 */
class RagPipelineIntegrationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper     objectMapper;
    @MockBean  OpenAiClient     openAiClient;

    private String jwt;

    @BeforeEach
    void registerAndLogin() throws Exception {
        String email = "ragtest_" + System.nanoTime() + "@example.com";

        // Register
        restTemplate.postForEntity("/auth/signup",
                new SignupRequest("RAG User", email, "Password1!"), String.class);

        // Login and extract JWT
        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/auth/login",
                new com.ai.rag.dto.LoginRequest(email, "Password1!"),
                String.class);

        JsonNode body = objectMapper.readTree(loginResp.getBody());
        jwt = body.get("accessToken").asText();
    }

    @Test
    @DisplayName("POST /api/rag/ask — GENERAL_LLM route returns 200 with answer")
    void ask_generalRoute_returns200() throws Exception {
        // Mock: analyzer routes to GENERAL, openAI returns an answer
        when(openAiClient.complete(anyString())).thenReturn("Hello! How can I help you today?");

        HttpHeaders headers = bearerHeaders();
        AskRequest  req     = new AskRequest();
        req.setQuery("hello");

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/rag/ask", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("answer").asText()).contains("Hello");
        assertThat(body.get("sessionId").asText()).isNotBlank();
        assertThat(body.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("POST /api/rag/ask without Bearer token returns 401/403")
    void ask_withoutToken_returns4xx() {
        AskRequest req = new AskRequest();
        req.setQuery("test query");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/rag/ask", req, String.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("POST /api/rag/ask with empty query returns 400")
    void ask_emptyQuery_returns400() {
        HttpHeaders headers = bearerHeaders();
        AskRequest  req     = new AskRequest();
        req.setQuery("");   // blank — should fail @NotBlank validation

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/rag/ask", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /api/rag/history returns empty page for new user")
    void history_newUser_returnsEmptyPage() throws Exception {
        HttpHeaders headers = bearerHeaders();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/rag/history", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("totalElements").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("History is persisted after a successful ask")
    void ask_persistsHistory() throws Exception {
        when(openAiClient.complete(anyString())).thenReturn("Paris.");

        HttpHeaders headers = bearerHeaders();
        AskRequest  req     = new AskRequest();
        req.setQuery("what is the capital of France");

        restTemplate.exchange("/api/rag/ask", HttpMethod.POST,
                new HttpEntity<>(req, headers), String.class);

        // Now fetch history
        ResponseEntity<String> histResp = restTemplate.exchange(
                "/api/rag/history", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode body = objectMapper.readTree(histResp.getBody());
        assertThat(body.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpHeaders bearerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}

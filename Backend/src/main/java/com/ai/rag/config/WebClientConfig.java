package com.ai.rag.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * UPDATED — Groq + Ollama dual-client configuration.
 *
 * Two separate WebClient beans:
 *
 * 1. openAiWebClient  → Groq (https://api.groq.com/openai/v1)
 *    Used for: all chat completions (generate, grade, rewrite, HyDE)
 *    Auth: Bearer {GROQ_API_KEY}  (stored in OPENAI_API_KEY env var for zero code change)
 *
 * 2. embeddingWebClient → Ollama (http://localhost:11434/v1)
 *    Used for: ONLY the embed() method in OpenAiClient
 *    Auth: "Bearer ollama" — Ollama accepts any non-empty bearer string
 *    No API key needed — Ollama runs locally, completely free
 *
 * Why separate beans instead of one?
 * Groq and Ollama have different:
 *   - base URLs
 *   - auth tokens
 *   - timeout characteristics (Ollama can be slower on first load)
 * Keeping them separate makes each independently configurable and testable.
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final OpenAiProperties props;

    @Value("${ollama.base-url:http://localhost:11434/v1}")
    private String ollamaBaseUrl;

    @Value("${ollama.timeout-seconds:120}")
    private int ollamaTimeoutSeconds;

    /**
     * Groq client — used for all LLM chat calls.
     * Base URL and API key come from OpenAiProperties (openai.base-url / openai.api-key).
     * In application.yml these are set to Groq values.
     */
    @Bean("openAiWebClient")
    @Primary
    public WebClient openAiWebClient() {
        // BUG FIX: responseTimeout and ReadTimeoutHandler intentionally REMOVED.
        //
        // This bean is used for BOTH:
        //   (a) Blocking chat() calls in OpenAiClient  → timeout applied per .block() call
        //   (b) SSE streaming in StreamingRagController → timeout applied per Flux token
        //
        // responseTimeout(45s) was killing SSE connections after 45 seconds because
        // reactor-netty measures it from request sent → full response body received.
        // For streaming, the body never "completes", so it fires mid-stream and the
        // browser gets ERR_INCOMPLETE_CHUNKED_ENCODING.
        //
        // ReadTimeoutHandler(45s) would fire if Groq goes silent for 45s between tokens.
        //
        // REPLACEMENTS:
        //   Streaming  → StreamingRagController uses .timeout(Duration.ofSeconds(75))
        //                on the Flux — idle timeout per token, not total body timeout.
        //   Blocking   → OpenAiClient applies .timeout(Duration.ofSeconds(45)) before
        //                each .block() — correct per-call timeout, not TCP-layer.
        //
        // Only CONNECT_TIMEOUT stays — 5s for initial TCP handshake is fine.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Ollama client — used ONLY for embedding calls.
     * Runs locally — no API key, no cost, no rate limits.
     *
     * Timeout is longer (120s default) because:
     *   - First request after model load can take 10-30s on CPU
     *   - Large documents chunked into many parallel embed calls
     *
     * If Ollama is running on a different host (e.g. another machine on LAN),
     * override ollama.base-url in application.yml.
     */
    @Bean("embeddingWebClient")
    public WebClient embeddingWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(ollamaTimeoutSeconds))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(ollamaTimeoutSeconds, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                // Ollama requires a Bearer token format but ignores the value
                .defaultHeader("Authorization", "Bearer ollama")
                .defaultHeader("Content-Type", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
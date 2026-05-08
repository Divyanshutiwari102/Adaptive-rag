package com.ai.rag.controller;

import com.ai.rag.config.OpenAiProperties;
import com.ai.rag.config.RagProperties;
import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import com.ai.rag.enums.QueryRoute;
import com.ai.rag.exception.RateLimitExceededException;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.UserRepository;
import com.ai.rag.resilience.RateLimiterService;
import com.ai.rag.service.ConversationMemoryService;
import com.ai.rag.service.OpenAiClient;
import com.ai.rag.service.SpendCapService;
import com.ai.rag.util.MmrReranker;
import com.ai.rag.util.PromptSanitizer;
import com.ai.rag.util.QueryComplexityAnalyzer;
import com.ai.rag.util.VectorSearchHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE streaming endpoint for RAG queries.
 *
 * STABILITY CHANGES IN THIS VERSION:
 *
 * 1. @Qualifier("openAiWebClient") restored
 *    The commented-out @Qualifier caused Spring to inject the @Primary WebClient bean.
 *    In WebClientConfig, "openAiWebClient" is @Primary but "embeddingWebClient" is not.
 *    This happened to work, but is fragile — if @Primary changes, the wrong client
 *    is injected. Explicit @Qualifier makes the dependency unambiguous.
 *
 * 2. onErrorResume added to the Flux pipeline
 *    Without onErrorResume, ANY exception in the reactive chain (JSON parse error,
 *    WebClient error, timeout) terminates the Flux with an error signal.
 *    When the Flux errors after the first SSE bytes are written (response committed),
 *    Spring cannot send an error response — it just closes the connection abruptly.
 *    onErrorResume catches errors, emits an SSE error event so the frontend knows
 *    what happened, then emits [DONE] to cleanly terminate the stream.
 *
 * 3. doOnError retained before onErrorResume for logging
 *    doOnError is a side-effect operator — it logs but does not consume the error.
 *    The error still propagates to onErrorResume which handles it.
 *
 * 4. .timeout() on the inner Flux only (not the outer Mono)
 *    Timeout is applied on webClient.post()...bodyToFlux(), not the whole pipeline.
 *    This means: the 75s clock starts when the first token is expected from Groq,
 *    not when buildSystemPrompt() starts running. buildSystemPrompt() has its own
 *    blocking timeouts via OpenAiClient.
 *
 * 5. buildSystemPrompt() errors surface as SSE error events (not connection drops)
 *    The Mono.fromCallable() that wraps buildSystemPrompt() runs on boundedElastic.
 *    If it throws (e.g., Ollama unavailable), flatMapMany propagates the error to
 *    onErrorResume, which emits an SSE error event and [DONE]. The frontend displays
 *    the error message instead of seeing a broken stream.
 *
 * 6. doFinally() always runs regardless of how the stream ends
 *    Covers: onComplete, onError, cancel (user closes tab mid-stream).
 *    Span is always closed. Cost is always recorded. No leaks.
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@Slf4j
public class StreamingRagController {

    @Qualifier("openAiWebClient")   // explicit — do not comment out
    private final WebClient                 webClient;
    private final OpenAiProperties          props;
    private final RagProperties             ragProps;
    private final ObjectMapper              objectMapper;
    private final UserRepository            userRepository;
    private final RateLimiterService        rateLimiter;
    private final DocumentChunkRepository   chunkRepository;
    private final OpenAiClient              openAiClient;
    private final MmrReranker               mmrReranker;
    private final ConversationMemoryService memoryService;
    private final QueryComplexityAnalyzer   complexityAnalyzer;
    private final PromptSanitizer           promptSanitizer;
    private final SpendCapService           spendCapService;
    private final Tracer                    tracer;

    /**
     * POST /api/rag/ask/stream
     *
     * Returns a text/event-stream (SSE) response. Each event contains one token
     * of the LLM response as plain text. The stream terminates with data: [DONE].
     *
     * On error, the stream emits:
     *   data: __ERROR__:{message}
     *   data: [DONE]
     *
     * The frontend should check for the __ERROR__ prefix and display it.
     *
     * Security: JWT is validated on the Tomcat request thread by JwtAuthenticationFilter.
     * The SecurityContext is propagated to async/reactor threads via:
     *   1. SecurityContextHolder.MODE_INHERITABLETHREADLOCAL (SecurityConfig)
     *   2. DelegatingSecurityContextAsyncTaskExecutor (WebMvcAsyncConfig)
     * @AuthenticationPrincipal resolves correctly on the request thread.
     * userRepository.findByEmail() runs on boundedElastic (non-blocking boundary).
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(
            @Valid @RequestBody StreamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        // ── Resolve user on the request thread (SecurityContext is valid here) ──
        // This must happen synchronously before the Flux pipeline starts,
        // because the SecurityContext may not be set on Reactor scheduler threads
        // in all edge cases (e.g., if the user store is also checked asynchronously).
        final User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Authenticated user not found in DB: "
                        + userDetails.getUsername()));

        // Rate limit check — fast Redis check, synchronous
        if (!rateLimiter.tryConsume("ask", user.getId().toString()))
            return Flux.error(new RateLimitExceededException(60L));

        // Spend cap check — synchronous, throws if over limit
        try {
            spendCapService.checkAndEnforce(user);
        } catch (Exception e) {
            return Flux.error(e);
        }

        log.info("[Stream] user={} query_len={}", user.getEmail(), request.query().length());

        final Span span = tracer.nextSpan().name("rag.stream").start();
        span.tag("user", user.getEmail());

        final String sessionId = resolveSessionId(request.sessionId());

        final AtomicInteger promptTokens     = new AtomicInteger(0);
        final AtomicInteger completionTokens = new AtomicInteger(0);
        final AtomicInteger totalTokens      = new AtomicInteger(0);

        return Mono.fromCallable(() -> buildSystemPrompt(request, user, sessionId, span))
                // buildSystemPrompt calls OpenAiClient (blocking HTTP) and DB queries.
                // subscribeOn(boundedElastic) moves these blocking calls off the event loop.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(systemPrompt -> {
                    ObjectNode body = buildStreamBody(request.query(), systemPrompt);

                    return webClient.post()
                            .uri("/chat/completions")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToFlux(String.class)
                            // ── THE REAL BUG WAS HERE ──────────────────────────────────────
                            //
                            // Spring WebFlux WebClient, when reading a "text/event-stream"
                            // response as String.class, uses ServerSentEventHttpMessageReader
                            // which AUTOMATICALLY:
                            //   1. Splits the body line-by-line (one SSE event per emission)
                            //   2. Strips the "data: " prefix — returns just the payload
                            //
                            // So the raw chunks look like this (confirmed via debug logs):
                            //   >>>{"choices":[{"delta":{"content":"The"}}]}<<<
                            //   >>>{"choices":[{"delta":{"content":" provided"}}]}<<<
                            //   >>>[DONE]<<<
                            //
                            // The old code had:
                            //   .filter(line -> line.startsWith("data: "))  ← FILTERED EVERYTHING OUT
                            //   .map(line -> line.substring(6).trim())      ← NEVER REACHED
                            //
                            // Because "data: " was already stripped, startsWith("data: ")
                            // returned false for every chunk → everything discarded → tokens=0.
                            //
                            // Fix: remove the "data: " filter and substring entirely.
                            // Just filter empty lines and the [DONE] sentinel.
                            .filter(line -> !line.isBlank())  // skip empty keep-alive lines
                            // takeUntil stops the stream when [DONE] is received
                            // but still emits [DONE] itself — filter removes it after
                            .takeUntil("[DONE]"::equals)
                            .filter(line -> !"[DONE]".equals(line))
                            .flatMap(json -> processChunk(json, promptTokens, completionTokens, totalTokens))
                            // Timeout on the Groq response stream only.
                            // If Groq stalls for > (configured timeout + 30s), the Flux
                            // times out. doFinally still runs via the outer pipeline.
                            .timeout(Duration.ofSeconds(props.getTimeoutSeconds() + 30))
                            // Map each text token to an SSE event
                            .map(token -> ServerSentEvent.<String>builder()
                                    .data(token)
                                    .build())
                            // Always append [DONE] after the last token
                            .concatWith(Flux.just(
                                    ServerSentEvent.<String>builder().data("[DONE]").build()
                            ));
                })
                // ── Error handling ─────────────────────────────────────────────────
                // doOnError logs the error (side-effect, does not consume the error signal)
                .doOnError(e -> log.error("[Stream] error for user={}: {}", user.getEmail(), e.getMessage(), e))
                // onErrorResume converts any error into an SSE error event + [DONE].
                // This is critical: without it, errors after the first SSE bytes are written
                // cause an abrupt connection close (ERR_INCOMPLETE_CHUNKED_ENCODING).
                // With it, the frontend receives a parseable error message.
                .onErrorResume(e -> {
                    String errorMsg = sanitizeErrorMessage(e);
                    log.warn("[Stream] sending error SSE to client: {}", errorMsg);
                    return Flux.just(
                            ServerSentEvent.<String>builder().data("__ERROR__:" + errorMsg).build(),
                            ServerSentEvent.<String>builder().data("[DONE]").build()
                    );
                })
                // ── Cleanup ────────────────────────────────────────────────────────
                // doFinally runs on EVERY terminal signal: onComplete, onError, cancel.
                // Span is always closed. Cost is always recorded. No resource leaks.
                .doFinally(signal -> {
                    int pTok = promptTokens.get();
                    int cTok = completionTokens.get();
                    int tTok = totalTokens.get();
                    double costUsd = (pTok / 1000.0) * 0.005 + (cTok / 1000.0) * 0.015;

                    // Persist cost asynchronously — do not block the SSE connection close
                    Schedulers.boundedElastic().schedule(() -> {
                        QueryHistory history = QueryHistory.builder()
                                .user(user)
                                .sessionId(sessionId)
                                .originalQuery(request.query())
                                .finalAnswer("[streamed]")
                                .routeTaken(QueryRoute.INDEX)
                                .queryRewritten(false)
                                .promptTokens(pTok)
                                .completionTokens(cTok)
                                .totalTokens(tTok > 0 ? tTok : null)
                                .estimatedCostUsd(tTok > 0 ? BigDecimal.valueOf(costUsd) : null)
                                .build();
                        spendCapService.persistStreamCost(history, user.getId().toString(), costUsd);
                    });

                    span.tag("total_tokens", String.valueOf(tTok));
                    span.tag("signal", signal.toString());
                    span.end();
                    log.info("[Stream] done signal={} tokens={} cost=${}", signal, tTok, costUsd);
                })
                // Propagate trace context into the Reactor context for distributed tracing
                .contextWrite(ctx -> ctx.put(TraceContext.class, span.context()));
    }

    // ── Chunk processing ──────────────────────────────────────────────────────

    /**
     * Parse one streamed JSON chunk from Groq/OpenAI.
     * Returns Flux.just(token) for content chunks, Flux.empty() for usage/empty chunks.
     * Never throws — parse errors return Flux.empty() (skip bad chunks silently).
     */
    private Flux<String> processChunk(String json,
                                      AtomicInteger promptTokens,
                                      AtomicInteger completionTokens,
                                      AtomicInteger totalTokens) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Usage chunk (last in stream when stream_options.include_usage = true)
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                promptTokens.set(usageNode.path("prompt_tokens").asInt(0));
                completionTokens.set(usageNode.path("completion_tokens").asInt(0));
                totalTokens.set(usageNode.path("total_tokens").asInt(0));
                return Flux.empty();
            }

            // Content chunk
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull()) {
                    String text = delta.asText();
                    if (!text.isEmpty()) {
                        return Flux.just(text);
                    }
                }
            }
            return Flux.empty();
        } catch (Exception e) {
            log.trace("[Stream] skipping malformed chunk: {}", json);
            return Flux.empty();
        }
    }

    // ── System prompt builder (blocking, runs on boundedElastic) ─────────────

    private String buildSystemPrompt(StreamRequest request, User user, String sessionId, Span span) {
        QueryComplexityAnalyzer.SearchStrategy strategy = complexityAnalyzer.analyze(request.query());
        span.tag("strategy", strategy.name());

        if (strategy == QueryComplexityAnalyzer.SearchStrategy.GENERAL_LLM) {
            return "You are a helpful AI assistant. Answer clearly and concisely.";
        }

        float[] queryVec = openAiClient.embed(request.query());
        int fetch = ragProps.getMaxRetrievalResults() * 2;

        List<DocumentChunk> candidates = chunkRepository.findTopKByCosine(
                user.getId(),
                VectorSearchHelper.toVectorString(queryVec),
                ragProps.getSimilarityThreshold(),
                fetch);

        String context = "";
        if (!candidates.isEmpty()) {
            List<DocumentChunk> reranked = mmrReranker.rerank(
                    candidates, queryVec, ragProps.getMaxRetrievalResults());
            context = buildContext(reranked);
        }

        String memory  = memoryService.buildMemoryBlock(user.getId().toString(), sessionId);
        String safeCtx = promptSanitizer.sanitizeContext(context);
        span.tag("context_chars", String.valueOf(safeCtx.length()));

        return "You are a precise AI assistant. Answer using ONLY the provided context. " +
                "Do not hallucinate.\n\n" +
                (safeCtx.isBlank() ? "" : "Context:\n" + safeCtx + "\n\n") +
                (memory.isBlank()  ? "" : "Prior conversation:\n" + memory);
    }

    private ObjectNode buildStreamBody(String query, String systemPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",       props.getChatModel());
        body.put("stream",      true);
        body.put("max_tokens",  props.getMaxTokens());
        body.put("temperature", props.getTemperature());
        body.putObject("stream_options").put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", query);
        return body;
    }

    private String buildContext(List<DocumentChunk> chunks) {
        int maxChars = ragProps.getMaxContextTokens() * 4;
        StringBuilder sb = new StringBuilder();
        for (DocumentChunk c : chunks) {
            String block = "[Chunk " + (c.getChunkIndex() + 1) + "]\n" + c.getContent();
            if (sb.length() + block.length() > maxChars) break;
            if (!sb.isEmpty()) sb.append("\n\n---\n\n");
            sb.append(block);
        }
        return sb.toString();
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : "stream-" + UUID.randomUUID();
    }

    /**
     * Sanitize error messages before sending to the client.
     * Never expose internal exception class names or stack traces.
     */
    private String sanitizeErrorMessage(Throwable e) {
        if (e == null) return "An unexpected error occurred";
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "An unexpected error occurred";
        // Truncate very long messages (WebClient errors can include full response bodies)
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    public record StreamRequest(
            @NotBlank @Size(max = 2000) String query,
            @Size(max = 128)            String sessionId) {}
}
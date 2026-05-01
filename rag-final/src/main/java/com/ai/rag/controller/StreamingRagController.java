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
 * UPDATED — FIX #1, #3, #4.
 *
 * FIX #1 (Streaming endpoint bypasses spend cap):
 *   spendCapService.checkAndEnforce(user) called at the VERY START of askStream(),
 *   before ANY processing — identical enforcement to the /ask path.
 *   checkAndEnforce() is in SpendCapService (shared service used by both endpoints).
 *
 * FIX #3 (Streaming cost persistence loss):
 *   persistStreamHistory() replaced by spendCapService.persistStreamCost() which:
 *   1. Writes cost to Redis counter atomically (always succeeds unless Redis is down)
 *   2. Persists to DB with 3-attempt retry
 *   3. Emits rag.stream.persist.failure metric if DB fails after all retries
 *
 * FIX #4 (Streaming timeout missing):
 *   .timeout(Duration.ofSeconds(props.getTimeoutSeconds() + 30)) applied on the
 *   inner Flux, BEFORE doFinally(). This ensures OpenAI stalls are caught and the
 *   span is still closed in doFinally() after the timeout error.
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@Slf4j
public class StreamingRagController {

    //@Qualifier("openAiWebClient")
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
    private final SpendCapService           spendCapService;  // FIX #1 + #3
    private final Tracer                    tracer;

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(
            @Valid @RequestBody StreamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!rateLimiter.tryConsume("ask", user.getId().toString()))
            return Flux.error(new RateLimitExceededException(60L));

        // FIX #1: Spend cap enforced at the VERY START, before any processing.
        // Same SpendCapService used by /ask — identical enforcement, no bypass possible.
        try {
            spendCapService.checkAndEnforce(user);
        } catch (Exception e) {
            return Flux.error(e);
        }

        log.info("[Stream] user={} query_len={}", user.getEmail(), request.query().length());

        Span span = tracer.nextSpan().name("rag.stream").start();
        span.tag("user", user.getEmail());

        String sessionId = resolveSessionId(request.sessionId());

        AtomicInteger promptTokens     = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);
        AtomicInteger totalTokens      = new AtomicInteger(0);

        return Mono.fromCallable(() -> buildSystemPrompt(request, user, sessionId, span))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(systemPrompt -> {
                    ObjectNode body = buildStreamBody(request.query(), systemPrompt);
                    return webClient.post()
                            .uri("/chat/completions")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToFlux(String.class)
                            .filter(line -> line.startsWith("data: "))
                            .map(line -> line.substring(6).trim())
                            .takeUntil("[DONE]"::equals)
                            .filter(line -> !"[DONE]".equals(line))
                            .flatMap(json -> processChunk(json, promptTokens, completionTokens, totalTokens))
                            // FIX #4: Timeout applied on the inner Flux, BEFORE doFinally.
                            // If OpenAI stalls for > (configured timeout + 30s), the Flux
                            // errors with TimeoutException. doFinally still runs (span closes,
                            // partial cost is recorded). Without this, a stalled stream holds
                            // a boundedElastic thread and a Netty connection indefinitely.
                            .timeout(Duration.ofSeconds(props.getTimeoutSeconds() + 30))
                            .map(token -> ServerSentEvent.<String>builder().data(token).build())
                            .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
                })
                .doFinally(signal -> {
                    int pTok = promptTokens.get();
                    int cTok = completionTokens.get();
                    int tTok = totalTokens.get();
                    double costUsd = (pTok / 1000.0) * 0.005 + (cTok / 1000.0) * 0.015;

                    // FIX #3: persist cost via SpendCapService (Redis-first, DB with retry)
                    Schedulers.boundedElastic().schedule(() -> {
                        QueryHistory history = QueryHistory.builder()
                                .user(user).sessionId(sessionId).originalQuery(request.query())
                                .finalAnswer("[streamed]").routeTaken(QueryRoute.INDEX)
                                .queryRewritten(false)
                                .promptTokens(pTok).completionTokens(cTok)
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
                .doOnError(e -> log.error("[Stream] error: {}", e.getMessage()))
                .contextWrite(ctx -> ctx.put(TraceContext.class, span.context()));
    }

    // ── Chunk processing ──────────────────────────────────────────────────────

    private Flux<String> processChunk(String json,
                                      AtomicInteger promptTokens,
                                      AtomicInteger completionTokens,
                                      AtomicInteger totalTokens) {
        try {
            JsonNode root = objectMapper.readTree(json);

            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                promptTokens.set(usageNode.path("prompt_tokens").asInt(0));
                completionTokens.set(usageNode.path("completion_tokens").asInt(0));
                totalTokens.set(usageNode.path("total_tokens").asInt(0));
                return Flux.empty();
            }

            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull()) {
                    return Flux.just(delta.asText());
                }
            }
            return Flux.empty();
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    // ── Blocking pre-steps (runs on boundedElastic) ───────────────────────────

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

        // FIX #6: memory block does NOT include "Prior conversation:\n" prefix here.
        // buildMemoryBlock() returns just the turns (no header).
        // This controller appends the header inline below — single source.
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
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(block);
        }
        return sb.toString();
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : "stream-" + UUID.randomUUID();
    }

    public record StreamRequest(
            @NotBlank @Size(max = 2000) String query,
            @Size(max = 128)            String sessionId) {}
}

package com.ai.rag.service;

import com.ai.rag.config.OpenAiProperties;
import com.ai.rag.exception.RagProcessingException;
import com.ai.rag.metrics.RagMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * UPDATED — Groq (chat) + Ollama (embeddings) free-tier configuration.
 *
 * ROUTING:
 *   openAiWebClient  (Groq)   → complete(), generateFromContext(), generateWithMemory(),
 *                                gradeRelevance(), rewriteQuery(), generateHypotheticalDocument()
 *   embeddingWebClient (Ollama) → embed()
 *
 * COST:
 *   Groq free tier  : 500 req/day on llama-3.3-70b, 14,400 req/day on llama-3.1-8b-instant
 *   Ollama          : completely free, runs on your machine, unlimited calls
 *
 * COST TRACKING:
 *   Cost constants are set to 0.0 since both providers are free.
 *   The spend cap in SpendCapService still works — it just never triggers at $0.
 *   If you switch back to paid OpenAI, update the constants below.
 *
 * EMBEDDING DIMENSIONS:
 *   nomic-embed-large produces 1536-dimension vectors — identical to OpenAI text-embedding-3-small.
 *   The pgvector schema (V2 migration) uses vector(1536) — NO schema change needed.
 */
@Service
@Slf4j
public class OpenAiClient {

    /** Groq — used for all chat (LLM) calls */
    private final WebClient        webClient;

    /** Ollama — used ONLY for embedding calls */
    private final WebClient        embeddingWebClient;

    private final OpenAiProperties props;
    private final ObjectMapper     objectMapper;
    private final RagMetrics       ragMetrics;

    public OpenAiClient(
            @Qualifier("openAiWebClient")    WebClient webClient,
            @Qualifier("embeddingWebClient") WebClient embeddingWebClient,
            OpenAiProperties props,
            ObjectMapper objectMapper,
            RagMetrics ragMetrics) {
        this.webClient          = webClient;
        this.embeddingWebClient = embeddingWebClient;
        this.props              = props;
        this.objectMapper       = objectMapper;
        this.ragMetrics         = ragMetrics;
    }

    private static final String CB = "openai";

    // Free tier — costs are $0. Keep constants at 0.0.
    // If you switch back to paid OpenAI, restore original values:
    //   GPT4O_IN_PER_1K = 0.005, GPT4O_OUT_PER_1K = 0.015, etc.
    private static final double CHAT_COST_PER_1K  = 0.0;  // Groq is free
    private static final double EMBED_COST_PER_1K = 0.0;  // Ollama is free

    // ── System prompts (unchanged) ────────────────────────────────────────────

    private static final String GENERATE_PROMPT =
            "You are a precise and helpful AI assistant. " +
            "Answer using ONLY the provided context. " +
            "If the context doesn't fully address the question, say so clearly.";

    private static final String GENERAL_PROMPT =
            "You are a helpful AI assistant. Answer clearly and concisely.";

    private static final String GRADE_PROMPT =
            "You are a strict document relevance grader. " +
            "Respond with ONLY the word YES or NO. " +
            "YES = context contains enough information to answer. NO = irrelevant or insufficient.";

    private static final String REWRITE_PROMPT =
            "You are a search query optimizer. " +
            "Rewrite the query to improve vector similarity search. " +
            "Return ONLY the rewritten query — no explanation, no quotes.";

    private static final String HYDE_PROMPT =
            "Write a short factual paragraph from a document that would directly answer " +
            "this question. Write as if you are the document author. Keep it under 200 words.";

    // ── Chat methods — all route to Groq via openAiWebClient ─────────────────

    @CircuitBreaker(name = CB, fallbackMethod = "completeFallback")
    @Retry(name = CB)
    public UsageResult complete(String query) {
        return chat(props.getChatModel(), GENERAL_PROMPT, query, props.getTemperature());
    }

    @CircuitBreaker(name = CB, fallbackMethod = "generateFallback")
    @Retry(name = CB)
    public UsageResult generateFromContext(String question, String context) {
        return chat(props.getChatModel(), GENERATE_PROMPT,
                "Context:\n" + context + "\n\nQuestion: " + question,
                props.getTemperature());
    }

    @CircuitBreaker(name = CB, fallbackMethod = "generateWithMemoryFallback")
    @Retry(name = CB)
    public UsageResult generateWithMemory(String question, String context, String memoryBlock) {
        String systemPrompt = (memoryBlock == null || memoryBlock.isBlank())
                ? GENERATE_PROMPT
                : GENERATE_PROMPT + "\n\nPrior conversation:\n" + memoryBlock;
        return chat(props.getChatModel(), systemPrompt,
                "Context:\n" + context + "\n\nQuestion: " + question,
                props.getTemperature());
    }

    @CircuitBreaker(name = CB, fallbackMethod = "gradeFallback")
    @Retry(name = CB)
    public boolean gradeRelevance(String question, String context) {
        UsageResult r = chat(props.getMiniModel(), GRADE_PROMPT,
                "Question: " + question + "\n\nContext:\n" + context, 0.0);
        boolean pass = r.text().trim().toUpperCase().startsWith("YES");
        log.debug("[Groq] grade={}", pass ? "YES" : "NO");
        return pass;
    }

    @CircuitBreaker(name = CB, fallbackMethod = "rewriteFallback")
    @Retry(name = CB)
    public String rewriteQuery(String query) {
        return chat(props.getMiniModel(), REWRITE_PROMPT, query, 0.3).text().trim();
    }

    @CircuitBreaker(name = CB, fallbackMethod = "hydeFallback")
    @Retry(name = CB)
    public String generateHypotheticalDocument(String query) {
        return chat(props.getMiniModel(), HYDE_PROMPT, query, 0.5).text().trim();
    }

    // ── Embedding — routes to Ollama via embeddingWebClient ──────────────────

    /**
     * Produces a 1536-dimension embedding vector using Ollama nomic-embed-large.
     *
     * Why nomic-embed-large?
     *   - Produces exactly 1536 dimensions — same as OpenAI text-embedding-3-small
     *   - The pgvector schema uses vector(1536) — NO migration change needed
     *   - Runs completely locally — zero API cost, unlimited calls, no rate limits
     *   - Good quality: MTEB benchmark score comparable to OpenAI small embedding
     *
     * Ollama uses the same /v1/embeddings endpoint format as OpenAI.
     * The only difference is: response has "embeddings" (array of arrays)
     * instead of OpenAI's "data[0].embedding" (single array).
     * Both formats are handled below.
     */
    @Cacheable(value = "embeddings", key = "@embeddingCacheKeyUtil.key(#text)")
    @CircuitBreaker(name = "ollama", fallbackMethod = "embedFallback")
    @Retry(name = "ollama")
    public float[] embed(String text) {
        String input = text.length() > 8000 ? text.substring(0, 8000) : text;
        long start = System.currentTimeMillis();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getEmbeddingModel()); // nomic-embed-large
        body.put("input", input);

        try {
            JsonNode resp = embeddingWebClient  // ← Ollama, NOT Groq
                    .post().uri("/embeddings")
                    .bodyValue(body).retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(120)) // Ollama can be slow on first call
                    .block();

            if (resp == null) throw new RagProcessingException("Empty embedding response from Ollama");

            // Ollama response format:
            // { "embeddings": [[0.1, 0.2, ...]] }   (array of arrays)
            // OpenAI response format:
            // { "data": [{ "embedding": [0.1, 0.2, ...] }] }
            // We handle both for compatibility.
            ArrayNode arr = extractEmbeddingArray(resp);

            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();

            ragMetrics.recordEmbedLatency(System.currentTimeMillis() - start);
            ragMetrics.recordEmbedCost(0.0); // Free!

            log.debug("[Ollama] embedded {} chars → {} dims in {}ms",
                    input.length(), vec.length, System.currentTimeMillis() - start);
            return vec;

        } catch (WebClientResponseException e) {
            ragMetrics.incrementOpenAiError("embed");
            log.error("[Ollama] embed error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RagProcessingException("Ollama embedding failed: " + e.getMessage() +
                    "\nIs Ollama running? Run: ollama serve", e);
        } catch (Exception e) {
            if (e instanceof RagProcessingException) throw e;
            ragMetrics.incrementOpenAiError("embed");
            log.error("[Ollama] embed failed: {}", e.getMessage());
            throw new RagProcessingException("Ollama embedding failed. " +
                    "Ensure Ollama is running (ollama serve) and model is pulled " +
                    "(ollama pull nomic-embed-large). Error: " + e.getMessage(), e);
        }
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    public UsageResult completeFallback(String q, Throwable t) {
        throw new RagProcessingException("Groq is temporarily unavailable. Please retry shortly.");
    }
    public UsageResult generateFallback(String q, String ctx, Throwable t) {
        throw new RagProcessingException("Groq is temporarily unavailable. Please retry shortly.");
    }
    public UsageResult generateWithMemoryFallback(String q, String ctx, String mem, Throwable t) {
        throw new RagProcessingException("Groq is temporarily unavailable. Please retry shortly.");
    }
    public boolean gradeFallback(String q, String ctx, Throwable t) {
        log.warn("[Groq CB] gradeRelevance circuit open — failing CLOSED (false)");
        return false;
    }
    public String rewriteFallback(String q, Throwable t) {
        log.warn("[Groq CB] rewriteQuery circuit open — returning original query");
        return q;
    }
    public String hydeFallback(String q, Throwable t) {
        log.warn("[Groq CB] HyDE circuit open — returning original query");
        return q;
    }
    public float[] embedFallback(String text, Throwable t) {
        throw new RagProcessingException(
                "Ollama embedding unavailable. Ensure 'ollama serve' is running and " +
                "'ollama pull nomic-embed-large' has been executed. Error: " + t.getMessage());
    }

    // ── Core chat (Groq) ──────────────────────────────────────────────────────

    private UsageResult chat(String model, String system, String user, double temperature) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", temperature);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        long start = System.currentTimeMillis();
        try {
            JsonNode resp = webClient  // ← Groq
                    .post().uri("/chat/completions")
                    .bodyValue(body).retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .block();

            if (resp == null) throw new RagProcessingException("Empty response from Groq");

            String text   = resp.path("choices").get(0).path("message").path("content").asText();
            int promptTok = resp.path("usage").path("prompt_tokens").asInt(0);
            int compTok   = resp.path("usage").path("completion_tokens").asInt(0);
            int totalTok  = resp.path("usage").path("total_tokens").asInt(0);

            ragMetrics.recordChatLatency(System.currentTimeMillis() - start);
            ragMetrics.recordChatCost(0.0); // Groq free tier

            log.debug("[Groq] model={} tokens={} latency={}ms",
                    model, totalTok, System.currentTimeMillis() - start);

            return new UsageResult(text, promptTok, compTok, totalTok, 0.0);

        } catch (WebClientResponseException e) {
            ragMetrics.incrementOpenAiError("chat");
            log.error("[Groq] chat error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());

            // Helpful message for the most common Groq errors
            if (e.getStatusCode().value() == 429) {
                throw new RagProcessingException(
                        "Groq rate limit reached. Free tier: 500 req/day on llama-3.3-70b, " +
                        "14,400 req/day on llama-3.1-8b-instant. Try again tomorrow.");
            }
            throw new RagProcessingException("Groq chat failed: " + e.getMessage(), e);
        }
    }

    /**
     * Handles both Ollama and OpenAI embedding response formats.
     *
     * Ollama format: { "embeddings": [[0.1, 0.2, ...]] }
     * OpenAI format: { "data": [{ "embedding": [0.1, 0.2, ...] }] }
     */
    private ArrayNode extractEmbeddingArray(JsonNode resp) {
        // Try Ollama format first
        JsonNode ollamaEmbeddings = resp.path("embeddings");
        if (!ollamaEmbeddings.isMissingNode() && ollamaEmbeddings.isArray()
                && ollamaEmbeddings.size() > 0) {
            JsonNode first = ollamaEmbeddings.get(0);
            if (first.isArray()) {
                return (ArrayNode) first;
            }
        }

        // Fall back to OpenAI format (in case Ollama changes its response format)
        JsonNode openAiData = resp.path("data");
        if (!openAiData.isMissingNode() && openAiData.isArray() && openAiData.size() > 0) {
            JsonNode embedding = openAiData.get(0).path("embedding");
            if (embedding.isArray()) {
                return (ArrayNode) embedding;
            }
        }

        throw new RagProcessingException(
                "Could not parse embedding response from Ollama. " +
                "Response: " + resp.toString().substring(0, Math.min(200, resp.toString().length())));
    }

    public record UsageResult(
            String text,
            int    promptTokens,
            int    completionTokens,
            int    totalTokens,
            double estimatedCostUsd) {}
}

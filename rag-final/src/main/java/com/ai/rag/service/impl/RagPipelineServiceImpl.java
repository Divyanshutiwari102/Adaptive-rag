package com.ai.rag.service.impl;

import com.ai.rag.config.RagProperties;
import com.ai.rag.dto.AskRequest;
import com.ai.rag.dto.AskResponse;
import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import com.ai.rag.enums.QueryRoute;
import com.ai.rag.exception.ResourceNotFoundException;
import com.ai.rag.metrics.RagMetrics;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.QueryHistoryRepository;
import com.ai.rag.service.ConversationMemoryService;
import com.ai.rag.service.OpenAiClient;
import com.ai.rag.service.OpenAiClient.UsageResult;
import com.ai.rag.service.RagPipelineService;
import com.ai.rag.service.SpendCapService;
import com.ai.rag.util.MmrReranker;
import com.ai.rag.util.QueryComplexityAnalyzer;
import com.ai.rag.util.QueryComplexityAnalyzer.SearchStrategy;
import com.ai.rag.util.VectorSearchHelper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * UPDATED — FIX #1 + FIX #2.
 *
 * FIX #1: Delegates spend cap enforcement to SpendCapService.checkAndEnforce().
 *   The private checkDailySpendCap() method is replaced — SpendCapService is the
 *   single source of enforcement for both /ask and /ask/stream.
 *
 * FIX #2: After a successful response, calls spendCapService.recordSpend() to
 *   atomically increment the Redis counter. This ensures the Redis counter stays
 *   in sync with actual spend, making subsequent cap checks accurate.
 *
 * FIX #7: gradeRelevance() metric recording removed from OpenAiClient.
 *   ragMetrics.recordGrade() is called here in the pipeline, not in OpenAiClient.
 *   This is the single recording point — no duplication.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagPipelineServiceImpl implements RagPipelineService {

    private final DocumentChunkRepository   chunkRepository;
    private final QueryHistoryRepository    historyRepository;
    private final OpenAiClient              openAiClient;
    private final QueryComplexityAnalyzer   complexityAnalyzer;
    private final MmrReranker               mmrReranker;
    private final ConversationMemoryService memoryService;
    private final RagProperties             ragProps;
    private final RagMetrics                ragMetrics;
    private final Tracer                    tracer;
    private final SpendCapService           spendCapService;  // FIX #1 + #2

    private static final String FALLBACK_ANSWER =
            "I don't have sufficient information in the indexed documents to answer your question. " +
            "Please try rephrasing or upload a document covering this topic.";

    @Override
    @Transactional
    public AskResponse process(AskRequest request, User user) {
        long   startMs       = System.currentTimeMillis();
        String sessionId     = resolveSessionId(request.getSessionId());
        String originalQuery = request.getQuery().strip();

        // FIX #1: Delegates to shared SpendCapService — same enforcement as /ask/stream.
        spendCapService.checkAndEnforce(user);

        Span span = tracer.nextSpan().name("rag.pipeline").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("user", user.getEmail());
            span.tag("session", sessionId);
            log.info("[RAG] user={} session={} query='{}'", user.getEmail(), sessionId, originalQuery);

            // FIX #6: buildMemoryBlock returns turns without "Prior conversation:\n" header.
            // generateWithMemory() and generateFromContext() add the header in the system prompt.
            String memoryBlock = memoryService.buildMemoryBlock(user.getId().toString(), sessionId);
            SearchStrategy strategy = complexityAnalyzer.analyze(originalQuery);
            span.tag("strategy", strategy.name());

            String      finalAnswer;
            QueryRoute  routeTaken;
            boolean     queryRewritten   = false;
            String      rewrittenQuery   = null;
            String      retrievedContext = null;
            Boolean     gradePass        = null;
            UsageResult lastUsage        = null;

            if (strategy == SearchStrategy.GENERAL_LLM) {
                String ctx = memoryBlock.isBlank()
                        ? originalQuery
                        : "Prior conversation:\n" + memoryBlock + "\n\nCurrent question: " + originalQuery;
                lastUsage   = openAiClient.complete(ctx);
                finalAnswer = lastUsage.text();
                routeTaken  = QueryRoute.GENERAL;
                ragMetrics.incrementRequests("GENERAL");

            } else {
                RetrievalResult retrieval = retrieve(originalQuery, strategy, user);
                log.debug("[RAG] retrieved {} candidates", retrieval.chunks().size());

                if (!retrieval.chunks().isEmpty()) {
                    List<DocumentChunk> candidates = mmrReranker.rerank(
                            retrieval.chunks(), retrieval.queryVec(), ragProps.getMaxRetrievalResults());
                    retrievedContext = buildContext(candidates);
                    // FIX #7: gradeRelevance() no longer records metric inside OpenAiClient.
                    // We record it here — single recording point.
                    gradePass = openAiClient.gradeRelevance(originalQuery, retrievedContext);
                    ragMetrics.recordGrade(gradePass);
                } else {
                    gradePass = false;
                    ragMetrics.recordGrade(false);
                }

                if (Boolean.FALSE.equals(gradePass)) {
                    log.info("[RAG] grade=FAIL → rewriting query");
                    rewrittenQuery = openAiClient.rewriteQuery(originalQuery);
                    queryRewritten = true;
                    ragMetrics.incrementQueryRewritten();

                    RetrievalResult retryResult = retrieve(rewrittenQuery, SearchStrategy.VECTOR_SEARCH, user);
                    if (!retryResult.chunks().isEmpty()) {
                        List<DocumentChunk> retryCandidates = mmrReranker.rerank(
                                retryResult.chunks(), retryResult.queryVec(), ragProps.getMaxRetrievalResults());
                        retrievedContext = buildContext(retryCandidates);
                        gradePass = openAiClient.gradeRelevance(rewrittenQuery, retrievedContext);
                        ragMetrics.recordGrade(gradePass);
                    }
                }

                if (Boolean.TRUE.equals(gradePass) && retrievedContext != null) {
                    String q = queryRewritten ? rewrittenQuery : originalQuery;
                    lastUsage = request.getSessionId() != null
                            ? openAiClient.generateWithMemory(q, retrievedContext, memoryBlock)
                            : openAiClient.generateFromContext(q, retrievedContext);
                    finalAnswer = lastUsage.text();
                    routeTaken  = QueryRoute.INDEX;
                } else {
                    finalAnswer = FALLBACK_ANSWER;
                    routeTaken  = QueryRoute.INDEX;
                    ragMetrics.incrementFallbackServed();
                }
                ragMetrics.incrementRequests("INDEX");
            }

            long latencyMs = System.currentTimeMillis() - startMs;
            ragMetrics.recordPipelineLatency(routeTaken.name(), latencyMs);
            span.tag("route", routeTaken.name());
            span.tag("latency_ms", String.valueOf(latencyMs));

            Integer    totalTokens   = lastUsage != null ? lastUsage.totalTokens()         : null;
            Integer    promptTokens  = lastUsage != null ? lastUsage.promptTokens()         : null;
            Integer    compTokens    = lastUsage != null ? lastUsage.completionTokens()     : null;
            BigDecimal estimatedCost = lastUsage != null
                    ? BigDecimal.valueOf(lastUsage.estimatedCostUsd()) : null;

            // FIX #2: Record spend atomically in Redis after successful response.
            if (lastUsage != null) {
                spendCapService.recordSpend(user.getId().toString(), lastUsage.estimatedCostUsd());
            }

            QueryHistory history = QueryHistory.builder()
                    .user(user).sessionId(sessionId).originalQuery(originalQuery)
                    .rewrittenQuery(rewrittenQuery)
                    .retrievedContext(capAtSentenceBoundary(retrievedContext, 2000))
                    .finalAnswer(finalAnswer).routeTaken(routeTaken)
                    .retrievalGradePass(gradePass).queryRewritten(queryRewritten)
                    .latencyMs(latencyMs).promptTokens(promptTokens)
                    .completionTokens(compTokens).totalTokens(totalTokens)
                    .estimatedCostUsd(estimatedCost)
                    .build();
            historyRepository.save(history);

            memoryService.appendTurn(user.getId().toString(), sessionId, originalQuery, finalAnswer);

            log.info("[RAG] done latency={}ms route={} tokens={} cost=${}",
                    latencyMs, routeTaken, totalTokens, estimatedCost);

            return AskResponse.builder()
                    .queryId(history.getId()).sessionId(sessionId).query(originalQuery)
                    .answer(finalAnswer).routeTaken(routeTaken)
                    .queryRewritten(queryRewritten).rewrittenQuery(rewrittenQuery)
                    .latencyMs(latencyMs).totalTokens(totalTokens)
                    .estimatedCostUsd(estimatedCost)
                    .build();

        } finally {
            span.end();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QueryHistory> getHistory(User user, String sessionId, Pageable pageable) {
        if (sessionId != null && !sessionId.isBlank())
            return historyRepository.findByUserAndSessionIdOrderByCreatedAtDesc(user, sessionId, pageable);
        return historyRepository.findByUserOrderByCreatedAtDesc(user, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public QueryHistory getHistoryById(UUID id, User user) {
        QueryHistory h = historyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("QueryHistory", id));
        if (!h.getUser().getId().equals(user.getId()))
            throw new ResourceNotFoundException("QueryHistory", id);
        return h;
    }

    // ── Retrieval ──────────────────────────────────────────────────────────────

    private RetrievalResult retrieve(String query, SearchStrategy strategy, User user) {
        int fetch = ragProps.getMaxRetrievalResults() * 2;

        if (strategy == SearchStrategy.KEYWORD_SEARCH) {
            List<DocumentChunk> chunks = chunkRepository.findByFullTextSearch(user.getId(), query, fetch);
            float[] vec = openAiClient.embed(query);
            return new RetrievalResult(chunks, vec);
        }

        float[] queryVec = resolveVectorQueryVec(query);
        List<DocumentChunk> chunks = chunkRepository.findTopKByCosine(
                user.getId(),
                VectorSearchHelper.toVectorString(queryVec),
                ragProps.getSimilarityThreshold(),
                fetch);
        return new RetrievalResult(chunks, queryVec);
    }

    private float[] resolveVectorQueryVec(String query) {
        if (ragProps.isHydeEnabled()) {
            try {
                String hypothetical = openAiClient.generateHypotheticalDocument(query);
                log.debug("[HyDE] generated {} chars for vector search", hypothetical.length());
                return openAiClient.embed(hypothetical);
            } catch (Exception e) {
                log.warn("[HyDE] failed, falling back to direct embed: {}", e.getMessage());
            }
        }
        return openAiClient.embed(query);
    }

    private record RetrievalResult(List<DocumentChunk> chunks, float[] queryVec) {}

    // ── Context assembly ───────────────────────────────────────────────────────

    private String buildContext(List<DocumentChunk> chunks) {
        int maxChars = ragProps.getMaxContextTokens() * 4;
        StringBuilder sb = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            String block = "[Chunk " + (chunk.getChunkIndex() + 1) + "]\n" + chunk.getContent();
            if (sb.length() + block.length() > maxChars) break;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(block);
        }
        return sb.toString();
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : "session-" + UUID.randomUUID();
    }

    private String capAtSentenceBoundary(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int boundary = s.lastIndexOf(". ", max);
        return (boundary > max / 2) ? s.substring(0, boundary + 1) + "..." : s.substring(0, max) + "...";
    }
}

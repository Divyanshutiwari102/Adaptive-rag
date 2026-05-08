package com.ai.rag.controller;

import com.ai.rag.dto.AskRequest;
import com.ai.rag.dto.AskResponse;
import com.ai.rag.dto.CostSummary;
import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import com.ai.rag.enums.QueryRoute;
import com.ai.rag.exception.RateLimitExceededException;
import com.ai.rag.repository.QueryHistoryRepository;
import com.ai.rag.repository.UserRepository;
import com.ai.rag.resilience.RateLimiterService;
import com.ai.rag.service.RagPipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * RAG Controller — Production-Grade
 *
 * KEY FIX vs. the original:
 *
 * resolveUser() now guards against null UserDetails before calling getUsername().
 * See DocumentController for the full explanation. The root cause is in SecurityConfig.
 *
 * NOTE on @Cacheable removal:
 * @Cacheable was intentionally removed from /ask to prevent spend-cap bypass.
 * A cached hit returned before checkDailySpendCap() inside process() ran,
 * allowing unlimited cached responses after the cap was hit. Do not re-add
 * @Cacheable here without placing the spend cap check BEFORE the cache lookup.
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagPipelineService     ragPipelineService;
    private final UserRepository         userRepository;
    private final QueryHistoryRepository historyRepository;
    private final RateLimiterService     rateLimiter;

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);

        if (!rateLimiter.tryConsume("ask", user.getId().toString())) {
            throw new RateLimitExceededException(60L);
        }

        return ResponseEntity.ok(ragPipelineService.process(request, user));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<HistorySummary>> getHistory(
            @RequestParam(required = false)    String sessionId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        Page<QueryHistory> history = ragPipelineService.getHistory(
                user, sessionId,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(history.map(HistorySummary::from));
    }

    @GetMapping("/history/{id}")
    public ResponseEntity<HistoryDetail> getHistoryById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        return ResponseEntity.ok(HistoryDetail.from(ragPipelineService.getHistoryById(id, user)));
    }

    @GetMapping("/cost/current-month")
    public ResponseEntity<CostSummary> getCostThisMonth(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        String userId = user.getId().toString();

        long       tokens      = historyRepository.sumTokensThisMonth(userId);
        BigDecimal monthlyCost = historyRepository.sumCostThisMonth(userId);
        BigDecimal todayCost   = historyRepository.sumCostToday(user.getId());

        return ResponseEntity.ok(CostSummary.builder()
                .periodMonth(YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM")))
                .totalTokens(tokens)
                .totalCostUsd(monthlyCost != null ? monthlyCost : BigDecimal.ZERO)
                .todayCostUsd(todayCost   != null ? todayCost   : BigDecimal.ZERO)
                .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the authenticated UserDetails to a User entity.
     * Null guard: defence-in-depth against SecurityConfig misconfiguration.
     */
    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) {
            throw new IllegalStateException(
                    "UserDetails is null — check SecurityConfig. /api/** must require authentication.");
        }
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + userDetails.getUsername() + "' not found in DB."));
    }

    // ── Response types ────────────────────────────────────────────────────────

    public record HistorySummary(
            UUID id, String sessionId, String query, String answer,
            QueryRoute routeTaken, boolean queryRewritten, Long latencyMs, LocalDateTime createdAt) {
        public static HistorySummary from(QueryHistory h) {
            return new HistorySummary(h.getId(), h.getSessionId(), h.getOriginalQuery(),
                    h.getFinalAnswer(), h.getRouteTaken(), h.isQueryRewritten(),
                    h.getLatencyMs(), h.getCreatedAt());
        }
    }

    public record HistoryDetail(
            UUID id, String sessionId, String originalQuery, String rewrittenQuery,
            String retrievedContext, String finalAnswer, QueryRoute routeTaken,
            Boolean retrievalGradePass, boolean queryRewritten, Long latencyMs, LocalDateTime createdAt) {
        public static HistoryDetail from(QueryHistory h) {
            return new HistoryDetail(h.getId(), h.getSessionId(), h.getOriginalQuery(),
                    h.getRewrittenQuery(), h.getRetrievedContext(), h.getFinalAnswer(),
                    h.getRouteTaken(), h.getRetrievalGradePass(), h.isQueryRewritten(),
                    h.getLatencyMs(), h.getCreatedAt());
        }
    }
}
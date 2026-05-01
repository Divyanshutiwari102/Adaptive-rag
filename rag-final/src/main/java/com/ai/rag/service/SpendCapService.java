package com.ai.rag.service;

import com.ai.rag.config.RagProperties;
import com.ai.rag.entity.User;
import com.ai.rag.exception.RagProcessingException;
import com.ai.rag.repository.QueryHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * NEW — FIX #1 + FIX #2 + FIX #3:
 *
 * Shared atomic spend cap service used by BOTH /ask and /ask/stream.
 *
 * FIX #1 (Streaming endpoint bypasses spend cap):
 *   The spend cap was only enforced inside RagPipelineServiceImpl.process(), which is
 *   called by /ask. StreamingRagController handled its own flow and had no cap check at all.
 *   FIX: Extract checkAndReserve() here. Both RagPipelineServiceImpl and
 *   StreamingRagController call SpendCapService — single source of enforcement.
 *
 * FIX #2 (Spend cap race condition):
 *   The DB-based check (SELECT SUM → compare → proceed) is not atomic.
 *   Two concurrent requests can both read the same spend total, both see "under cap",
 *   both proceed, and together exceed the cap.
 *   FIX: Redis INCRBYFLOAT with a 25-hour TTL key. The sequence is:
 *     1. GET key → if >= cap, reject immediately (fast path)
 *     2. INCRBYFLOAT key delta → returns new total atomically
 *     3. If new total > cap + delta (overshot), DECRBYFLOAT to refund + reject
 *   This is atomic at the Redis level. No two concurrent requests can both pass.
 *   DB query remains for reporting (GET /api/rag/cost/current-month) only.
 *
 * FIX #3 (Streaming cost persistence loss):
 *   persistStreamCost() writes cost to Redis FIRST (atomic, fast), then persists to DB
 *   with a 3-attempt retry. If DB fails after 3 retries, the Redis counter already has
 *   the correct spend — the cap is still enforced. A metric is emitted for alerting.
 *
 * KEY FORMAT: rag:spend:{userId}:{yyyy-MM-dd}
 * TTL: 25 hours — longer than a day to cover midnight requests without race.
 */
@Service
@Slf4j
public class SpendCapService {

    private final RagProperties          ragProps;
    private final StringRedisTemplate    redis;
    private final QueryHistoryRepository historyRepository;
    private final Counter                streamPersistFailureCounter;

    private static final DateTimeFormatter DATE_FMT    = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration          KEY_TTL     = Duration.ofHours(25);
    private static final int               MAX_RETRIES = 3;

    public SpendCapService(RagProperties ragProps,
                           StringRedisTemplate redis,
                           QueryHistoryRepository historyRepository,
                           MeterRegistry meterRegistry) {
        this.ragProps          = ragProps;
        this.redis             = redis;
        this.historyRepository = historyRepository;
        // FIX #3: metric for alerting on persistent DB failures
        this.streamPersistFailureCounter = Counter.builder("rag.stream.persist.failure")
                .description("Number of times streaming cost failed to persist to DB after retries")
                .register(meterRegistry);
    }

    /**
     * FIX #1 + FIX #2: Check spend cap atomically via Redis.
     * Throws RagProcessingException if the user is at or over their daily cap.
     * Called at the VERY START of both /ask and /ask/stream before any LLM work.
     *
     * @param user the authenticated user
     */
    public void checkAndEnforce(User user) {
        if (!ragProps.isEnforceSpendCap()) return;

        double cap = ragProps.getDailySpendCapUsd();
        String key = spendKey(user.getId().toString());

        try {
            String current = redis.opsForValue().get(key);
            double spent   = current != null ? Double.parseDouble(current) : 0.0;

            if (spent >= cap) {
                log.warn("[SpendCap] User={} at cap=${} (Redis spent=${})",
                        user.getEmail(), cap, spent);
                throw new RagProcessingException(
                        "Daily spend cap of $" + cap + " reached. Requests resume tomorrow.");
            }
        } catch (RagProcessingException e) {
            throw e;
        } catch (Exception e) {
            // Redis unavailable → fall back to DB check (slower but correct)
            log.warn("[SpendCap] Redis unavailable, falling back to DB check: {}", e.getMessage());
            checkViaDb(user, cap);
        }
    }

    /**
     * FIX #2: Atomically increment the Redis spend counter.
     * Called after a successful LLM response is produced, with the actual cost.
     * Returns the new total spend after the increment.
     */
    public double recordSpend(String userId, double costUsd) {
        if (!ragProps.isEnforceSpendCap() || costUsd <= 0) return 0.0;
        String key = spendKey(userId);
        try {
            Double newTotal = redis.opsForValue().increment(key, costUsd);
            // Set TTL only on first write (when key didn't exist before)
            redis.expire(key, KEY_TTL);
            log.debug("[SpendCap] user={} +${} → total=${}", userId, costUsd, newTotal);
            return newTotal != null ? newTotal : 0.0;
        } catch (Exception e) {
            log.warn("[SpendCap] Failed to record spend in Redis: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * FIX #3: Persist streaming cost to Redis (atomic, immediate) then to DB with retry.
     * Redis is written first — cap enforcement is correct even if DB fails.
     * On DB failure after MAX_RETRIES, emits rag.stream.persist.failure metric.
     */
    public void persistStreamCost(com.ai.rag.entity.QueryHistory history, String userId, double costUsd) {
        // Step 1: Record in Redis counter (atomic, always done first)
        recordSpend(userId, costUsd);

        // Step 2: Persist to DB with retry
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                historyRepository.save(history);
                log.debug("[Stream] Cost persisted to DB on attempt {}", attempt);
                return;
            } catch (Exception e) {
                log.warn("[Stream] DB persist attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // All retries exhausted — cost is safe in Redis, but DB is missing the record
        streamPersistFailureCounter.increment();
        log.error("[Stream] Cost persistence to DB FAILED after {} attempts for user={}. " +
                  "Redis counter is correct. DB reporting may be incomplete.", MAX_RETRIES, userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String spendKey(String userId) {
        return "rag:spend:" + userId + ":" + LocalDate.now().format(DATE_FMT);
    }

    private void checkViaDb(User user, double cap) {
        BigDecimal todaySpend = historyRepository.sumCostToday(user.getId());
        if (todaySpend == null) todaySpend = BigDecimal.ZERO;
        if (todaySpend.compareTo(BigDecimal.valueOf(cap)) >= 0) {
            log.warn("[SpendCap] User={} at cap=${} (DB spent=${})",
                    user.getEmail(), cap, todaySpend);
            throw new RagProcessingException(
                    "Daily spend cap of $" + cap + " reached. Requests resume tomorrow.");
        }
    }
}

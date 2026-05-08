package com.ai.rag.repository;

import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * UPDATED — FIX #9 (Native query Pageable misuse).
 *
 * PROBLEM: findRecentByUserAndSession() was a native SQL query with an explicit
 * LIMIT :limit clause, but its method signature accepted a Pageable parameter.
 * Native queries with Pageable require a separate countQuery for total count, and
 * the Pageable sort/offset are appended on top of the SQL — conflicting with the
 * explicit LIMIT already in the query. This causes either a runtime error or
 * silently incorrect results (double LIMIT, wrong offset).
 *
 * FIX: Replaced Pageable with a plain @Param("limit") int limit.
 * The SQL uses LIMIT :limit directly — clean, unambiguous, no Spring magic.
 * Caller (ConversationMemoryService) passes MAX_TURNS as a plain int.
 */
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, UUID> {

    Page<QueryHistory> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<QueryHistory> findByUserAndSessionIdOrderByCreatedAtDesc(
            User user, String sessionId, Pageable pageable);

    /**
     * FIX #9: Pageable replaced with plain int limit.
     * Native query with explicit LIMIT must not also receive a Pageable — they conflict.
     */
    @Query(value = """
            SELECT qh.*
            FROM query_history qh
            WHERE qh.user_id = CAST(:userId AS uuid)
              AND qh.session_id = :sessionId
            ORDER BY qh.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<QueryHistory> findRecentByUserAndSession(
            @Param("userId")    String userId,
            @Param("sessionId") String sessionId,
            @Param("limit")     int limit);

    @Query(value = """
            SELECT COALESCE(SUM(estimated_cost_usd), 0)
            FROM query_history
            WHERE user_id = CAST(:userId AS uuid)
              AND created_at >= CURRENT_DATE
              AND created_at <  CURRENT_DATE + INTERVAL '1 day'
            """, nativeQuery = true)
    BigDecimal sumCostToday(@Param("userId") UUID userId);

    @Query(value = """
            SELECT COALESCE(SUM(total_tokens), 0)
            FROM query_history
            WHERE user_id = CAST(:userId AS uuid)
              AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)
            """, nativeQuery = true)
    long sumTokensThisMonth(@Param("userId") String userId);

    @Query(value = """
            SELECT COALESCE(SUM(estimated_cost_usd), 0)
            FROM query_history
            WHERE user_id = CAST(:userId AS uuid)
              AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)
            """, nativeQuery = true)
    BigDecimal sumCostThisMonth(@Param("userId") String userId);
}

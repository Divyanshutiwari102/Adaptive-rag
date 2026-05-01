package com.ai.rag.service;

import com.ai.rag.entity.QueryHistory;
import com.ai.rag.repository.QueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * UPDATED — FIX #6 (Duplicate "Prior conversation" header).
 *
 * PROBLEM: buildMemoryBlock() returned a string that started with "Prior conversation:\n".
 * Every caller (OpenAiClient.generateWithMemory, StreamingRagController.buildSystemPrompt,
 * RagPipelineServiceImpl for GENERAL_LLM) then prepended "Prior conversation:\n" again
 * when injecting it into the system prompt. The header appeared twice in every prompt.
 *
 * FIX: buildMemoryBlock() now returns ONLY the raw conversation turns (no header).
 * The header "Prior conversation:\n" is added by each caller exactly once when building
 * the system prompt. Single source — no duplication possible.
 *
 * NOTE on Pageable vs int (FIX #9 cross-reference):
 * findRecentByUserAndSession() is a native query with an explicit LIMIT :limit parameter.
 * It does NOT accept Pageable — that was a mismatch. This service now passes the plain
 * int MAX_TURNS directly (see QueryHistoryRepository for the corrected signature).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationMemoryService {

    private final QueryHistoryRepository historyRepository;

    private static final int MAX_TURNS      = 5;
    private static final int MAX_CHARS_TURN = 500;

    /**
     * Loads the last MAX_TURNS Q&A pairs for a session.
     *
     * FIX #6: Returns ONLY the raw conversation turns — NO "Prior conversation:\n" header.
     * Callers are responsible for adding the header when building the system prompt.
     * This ensures the header appears exactly once, regardless of how many callers use this.
     *
     * @return formatted turns string, or empty string if no history
     */
    public String buildMemoryBlock(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "";

        // FIX #9: passes int directly — no Pageable mismatch with native query
        List<QueryHistory> turns = historyRepository
                .findRecentByUserAndSession(userId, sessionId, MAX_TURNS);

        if (turns.isEmpty()) return "";

        Collections.reverse(turns); // oldest first (chronological order)

        // FIX #6: NO "Prior conversation:\n" header — callers add it
        StringBuilder sb = new StringBuilder();
        for (QueryHistory turn : turns) {
            sb.append("User: ")
              .append(cap(turn.getOriginalQuery(), MAX_CHARS_TURN))
              .append("\nAssistant: ")
              .append(cap(turn.getFinalAnswer(), MAX_CHARS_TURN))
              .append("\n\n");
        }

        log.debug("[Memory] Injecting {} prior turns for session={}", turns.size(), sessionId);
        return sb.toString().strip();
    }

    public void appendTurn(String userId, String sessionId, String query, String answer) {
        // Turns are written as QueryHistory rows by the pipeline — no separate write needed.
        // This method is kept for interface compatibility and future Redis-backed sessions.
    }

    private String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

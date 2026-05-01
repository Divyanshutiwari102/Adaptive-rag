package com.ai.rag.service;

import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import com.ai.rag.enums.QueryRoute;
import com.ai.rag.repository.QueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryServiceTest {

    @Mock QueryHistoryRepository historyRepository;
    @InjectMocks ConversationMemoryService memoryService;

    private final String userId    = UUID.randomUUID().toString();
    private final String sessionId = "session-abc";

    @Test
    @DisplayName("Empty session → returns empty string")
    void emptySession_returnsEmpty() {
        when(historyRepository.findRecentByUserAndSession(any(), any(), any()))
                .thenReturn(List.of());

        String result = memoryService.buildMemoryBlock(userId, sessionId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Null sessionId → returns empty string without hitting DB")
    void nullSession_returnsEmpty() {
        String result = memoryService.buildMemoryBlock(userId, null);
        assertThat(result).isEmpty();
        verifyNoInteractions(historyRepository);
    }

    @Test
    @DisplayName("Prior turns are formatted in chronological order with User/Assistant labels")
    void priorTurns_formattedCorrectly() {
        QueryHistory turn1 = history("What is JWT?", "JWT is a token format.");
        QueryHistory turn2 = history("How is it signed?", "Using HMAC-SHA256.");

        // Repository returns DESC (newest first) — service reverses to chronological
        when(historyRepository.findRecentByUserAndSession(any(), any(), any()))
                .thenReturn(List.of(turn2, turn1));

        String block = memoryService.buildMemoryBlock(userId, sessionId);

        assertThat(block).startsWith("Prior conversation:");
        // After reversal: turn1 should appear before turn2
        int pos1 = block.indexOf("What is JWT?");
        int pos2 = block.indexOf("How is it signed?");
        assertThat(pos1).isLessThan(pos2);

        assertThat(block).contains("User: What is JWT?");
        assertThat(block).contains("Assistant: JWT is a token format.");
    }

    @Test
    @DisplayName("Very long answers are capped to avoid prompt bloat")
    void longAnswers_areTruncated() {
        String veryLong = "x".repeat(2000);
        QueryHistory turn = history("Short question?", veryLong);

        when(historyRepository.findRecentByUserAndSession(any(), any(), any()))
                .thenReturn(List.of(turn));

        String block = memoryService.buildMemoryBlock(userId, sessionId);

        // Should not contain the full 2000-char string
        assertThat(block).contains("…");
        assertThat(block.length()).isLessThan(2000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private QueryHistory history(String question, String answer) {
        return QueryHistory.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .originalQuery(question)
                .finalAnswer(answer)
                .routeTaken(QueryRoute.INDEX)
                .build();
    }
}

package com.ai.rag.service;

import com.ai.rag.config.RagProperties;
import com.ai.rag.dto.AskRequest;
import com.ai.rag.dto.AskResponse;
import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import com.ai.rag.enums.QueryRoute;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.QueryHistoryRepository;
import com.ai.rag.service.impl.RagPipelineServiceImpl;
import com.ai.rag.util.MmrReranker;
import com.ai.rag.util.QueryComplexityAnalyzer;
import com.ai.rag.util.QueryComplexityAnalyzer.SearchStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the RAG pipeline.
 * OpenAiClient is mocked — no real API calls.
 *
 * Tests all branches:
 *   - GENERAL_LLM route (no retrieval)
 *   - VECTOR_SEARCH with grade=PASS
 *   - VECTOR_SEARCH with grade=FAIL → rewrite → retry grade=PASS
 *   - VECTOR_SEARCH with grade=FAIL → rewrite → retry grade=FAIL (fallback)
 *   - Empty retrieval → fallback
 */
@ExtendWith(MockitoExtension.class)
class RagPipelineServiceImplTest {

    @Mock DocumentChunkRepository  chunkRepository;
    @Mock QueryHistoryRepository   historyRepository;
    @Mock OpenAiClient             openAiClient;
    @Mock QueryComplexityAnalyzer  complexityAnalyzer;
    @Mock MmrReranker              mmrReranker;

    @InjectMocks RagPipelineServiceImpl service;

    private RagProperties ragProps;
    private User          testUser;

    @BeforeEach
    void setUp() {
        ragProps = new RagProperties();
        ragProps.setMaxRetrievalResults(5);
        ragProps.setSimilarityThreshold(0.75);
        ragProps.setMaxContextTokens(3000);
        // Inject ragProps via reflection (field injection from @InjectMocks)
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ragProps", ragProps);

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .name("Test User")
                .build();

        // Default: save returns the passed entity with an ID
        when(historyRepository.save(any())).thenAnswer(inv -> {
            QueryHistory h = inv.getArgument(0);
            if (h.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
            }
            return h;
        });
    }

    @Nested
    @DisplayName("GENERAL_LLM route")
    class GeneralLlmRoute {

        @Test
        @DisplayName("Conversational query is answered directly without retrieval")
        void generalQuery_noRetrieval() {
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.GENERAL_LLM);
            when(openAiClient.complete(anyString())).thenReturn("The capital of France is Paris.");

            AskResponse response = service.process(askRequest("hi"), testUser);

            assertThat(response.getAnswer()).contains("Paris");
            assertThat(response.getRouteTaken()).isEqualTo(QueryRoute.GENERAL);
            verify(chunkRepository, never()).findTopKByCosine(any(), any(), anyDouble(), anyInt());
            verify(chunkRepository, never()).findByFullTextSearch(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("VECTOR_SEARCH route — grade passes")
    class VectorSearchPassRoute {

        @Test
        @DisplayName("Relevant chunks → generated answer returned")
        void vectorSearch_gradePass_returnsGeneratedAnswer() {
            DocumentChunk chunk = chunk("Spring Boot auto-configuration works by scanning classpath.");
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.VECTOR_SEARCH);
            when(openAiClient.embed(anyString())).thenReturn(new float[1536]);
            when(chunkRepository.findTopKByCosine(any(), any(), anyDouble(), anyInt()))
                    .thenReturn(List.of(chunk));
            when(mmrReranker.rerank(anyList(), any(), anyInt())).thenReturn(List.of(chunk));
            when(openAiClient.gradeRelevance(anyString(), anyString())).thenReturn(true);
            when(openAiClient.generateFromContext(anyString(), anyString()))
                    .thenReturn("Spring Boot auto-configures based on classpath.");

            AskResponse response = service.process(askRequest("How does Spring Boot work?"), testUser);

            assertThat(response.getAnswer()).contains("auto-configures");
            assertThat(response.getRouteTaken()).isEqualTo(QueryRoute.INDEX);
            assertThat(response.isQueryRewritten()).isFalse();
            verify(openAiClient, times(1)).generateFromContext(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("VECTOR_SEARCH route — grade fails, rewrite succeeds")
    class VectorSearchRewriteRoute {

        @Test
        @DisplayName("Grade fail → rewrite → retry → grade pass → generated answer")
        void gradeFailThenRewriteSucceeds() {
            DocumentChunk chunk = chunk("JWT tokens use HMAC-SHA256 for signing.");
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.VECTOR_SEARCH);
            when(openAiClient.embed(anyString())).thenReturn(new float[1536]);
            when(chunkRepository.findTopKByCosine(any(), any(), anyDouble(), anyInt()))
                    .thenReturn(List.of(chunk));
            when(mmrReranker.rerank(anyList(), any(), anyInt())).thenReturn(List.of(chunk));

            // First grade: fail. Second grade (after rewrite): pass.
            when(openAiClient.gradeRelevance(anyString(), anyString()))
                    .thenReturn(false)
                    .thenReturn(true);
            when(openAiClient.rewriteQuery(anyString())).thenReturn("JWT token signing algorithm");
            when(openAiClient.generateFromContext(anyString(), anyString()))
                    .thenReturn("JWT uses HS256.");

            AskResponse response = service.process(askRequest("auth stuff"), testUser);

            assertThat(response.isQueryRewritten()).isTrue();
            assertThat(response.getRewrittenQuery()).isEqualTo("JWT token signing algorithm");
            assertThat(response.getAnswer()).isEqualTo("JWT uses HS256.");
            verify(openAiClient, times(2)).gradeRelevance(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Fallback paths")
    class FallbackPaths {

        @Test
        @DisplayName("Empty retrieval → fallback answer")
        void emptyRetrieval_returnsFallback() {
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.VECTOR_SEARCH);
            when(openAiClient.embed(anyString())).thenReturn(new float[1536]);
            when(chunkRepository.findTopKByCosine(any(), any(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            AskResponse response = service.process(askRequest("some obscure query"), testUser);

            assertThat(response.getAnswer()).contains("don't have sufficient information");
            verify(openAiClient, never()).generateFromContext(anyString(), anyString());
        }

        @Test
        @DisplayName("Grade fail on retry → fallback answer")
        void gradeFailBothAttempts_returnsFallback() {
            DocumentChunk chunk = chunk("Unrelated content.");
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.VECTOR_SEARCH);
            when(openAiClient.embed(anyString())).thenReturn(new float[1536]);
            when(chunkRepository.findTopKByCosine(any(), any(), anyDouble(), anyInt()))
                    .thenReturn(List.of(chunk));
            when(mmrReranker.rerank(anyList(), any(), anyInt())).thenReturn(List.of(chunk));
            when(openAiClient.gradeRelevance(anyString(), anyString())).thenReturn(false);
            when(openAiClient.rewriteQuery(anyString())).thenReturn("rewritten query");

            AskResponse response = service.process(askRequest("irrelevant question"), testUser);

            assertThat(response.getAnswer()).contains("don't have sufficient information");
            verify(openAiClient, never()).generateFromContext(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Audit persistence")
    class AuditPersistence {

        @Test
        @DisplayName("QueryHistory is always saved regardless of route taken")
        void queryHistory_alwaysSaved() {
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.GENERAL_LLM);
            when(openAiClient.complete(anyString())).thenReturn("An answer.");

            service.process(askRequest("hello"), testUser);

            verify(historyRepository, times(1)).save(any(QueryHistory.class));
        }

        @Test
        @DisplayName("Latency is recorded in the response")
        void latency_isRecorded() {
            when(complexityAnalyzer.analyze(anyString())).thenReturn(SearchStrategy.GENERAL_LLM);
            when(openAiClient.complete(anyString())).thenReturn("Answer.");

            AskResponse response = service.process(askRequest("query"), testUser);

            assertThat(response.getLatencyMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AskRequest askRequest(String query) {
        AskRequest r = new AskRequest();
        r.setQuery(query);
        return r;
    }

    private DocumentChunk chunk(String content) {
        return DocumentChunk.builder()
                .id(UUID.randomUUID())
                .chunkIndex(0)
                .content(content)
                .embeddingVec(new float[1536])
                .build();
    }
}

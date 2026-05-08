package com.ai.rag.service;

import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.util.MmrReranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MmrRerankerTest {

    private MmrReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new MmrReranker();
    }

    @Test
    @DisplayName("Returns empty list when candidates is empty")
    void emptyInput_returnsEmpty() {
        assertThat(reranker.rerank(List.of(), new float[4], 3)).isEmpty();
    }

    @Test
    @DisplayName("Returns candidates unchanged when fewer than topK")
    void fewerThanTopK_returnsAll() {
        List<DocumentChunk> chunks = List.of(chunk(new float[]{1f, 0f, 0f, 0f}));
        assertThat(reranker.rerank(chunks, new float[]{1f, 0f, 0f, 0f}, 5)).hasSize(1);
    }

    @Test
    @DisplayName("Output size does not exceed topK")
    void outputDoesNotExceedTopK() {
        List<DocumentChunk> chunks = chunks(10, 4);
        List<DocumentChunk> result = reranker.rerank(chunks, new float[]{1f, 0f, 0f, 0f}, 3);
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("Diverse chunks are preferred over near-duplicates")
    void diversityPreferred() {
        // Chunk A: identical to query — high relevance but redundant
        // Chunk B: moderately relevant but orthogonal to A — higher MMR score
        // Chunk C: nearly identical to A — should be deprioritised
        float[] query  = {1f, 0f, 0f, 0f};
        float[] vecA   = {1f, 0f, 0f, 0f};   // cos_sim to query = 1.0
        float[] vecB   = {0f, 1f, 0f, 0f};   // cos_sim to query = 0.0, but diverse
        float[] vecC   = {0.99f, 0.01f, 0f, 0f}; // cos_sim ≈ 1.0, almost identical to A

        DocumentChunk chunkA = chunk(vecA);
        DocumentChunk chunkB = chunk(vecB);
        DocumentChunk chunkC = chunk(vecC);

        List<DocumentChunk> result = reranker.rerank(new ArrayList<>(List.of(chunkA, chunkB, chunkC)), query, 2);

        // A is always selected first (highest relevance).
        assertThat(result.get(0).getId()).isEqualTo(chunkA.getId());

        // Second selection: B is more diverse from A than C is.
        assertThat(result.get(1).getId()).isEqualTo(chunkB.getId());
    }

    @Test
    @DisplayName("Chunks with null embedding are skipped")
    void nullEmbedding_skipped() {
        DocumentChunk noEmbedding = DocumentChunk.builder()
                .id(UUID.randomUUID())
                .chunkIndex(0)
                .content("No embedding")
                .embeddingVec(null)
                .build();
        DocumentChunk withEmbedding = chunk(new float[]{1f, 0f, 0f, 0f});

        List<DocumentChunk> result = reranker.rerank(
                new ArrayList<>(List.of(noEmbedding, withEmbedding)), new float[]{1f, 0f, 0f, 0f}, 5);

        assertThat(result).contains(withEmbedding);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DocumentChunk chunk(float[] vec) {
        return DocumentChunk.builder()
                .id(UUID.randomUUID())
                .chunkIndex(0)
                .content("content")
                .embeddingVec(vec)
                .build();
    }

    private List<DocumentChunk> chunks(int count, int dim) {
        List<DocumentChunk> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] vec = new float[dim];
            vec[i % dim] = 1f;
            list.add(chunk(vec));
        }
        return list;
    }
}

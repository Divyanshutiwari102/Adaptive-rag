package com.ai.rag.integration;

import com.ai.rag.entity.*;
import com.ai.rag.repository.*;
import com.ai.rag.service.OpenAiClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for HNSW vector search against real pgvector.
 * FIX: This test was completely absent in v1.
 */
class VectorSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired DocumentChunkRepository chunkRepository;
    @Autowired DocumentRepository      documentRepository;
    @Autowired UserRepository          userRepository;
    @Autowired PasswordEncoder         passwordEncoder;
    @MockBean  OpenAiClient            openAiClient;

    private User testUser;
    private Document testDoc;

    @BeforeEach
    void setup() {
        testUser = userRepository.save(User.builder()
                .name("VectorTest").email("vec_" + System.nanoTime() + "@test.com")
                .password(passwordEncoder.encode("pass")).build());

        testDoc = documentRepository.save(Document.builder()
                .filename("test.pdf").fileType("application/pdf")
                .uploadedBy(testUser).ingestionStatus(Document.IngestionStatus.DONE).build());
    }

    @Test @DisplayName("findTopKByCosine returns nearest chunks from pgvector HNSW")
    void vectorSearch_returnsCorrectResults() {
        float[] targetVec = createTestVector(1.0f, 0.0f);
        float[] otherVec  = createTestVector(0.0f, 1.0f);

        DocumentChunk relevant = chunkRepository.save(DocumentChunk.builder()
                .document(testDoc).chunkIndex(0)
                .content("Machine learning and AI concepts").embeddingVec(targetVec).build());
        DocumentChunk irrelevant = chunkRepository.save(DocumentChunk.builder()
                .document(testDoc).chunkIndex(1)
                .content("Cooking recipes and food").embeddingVec(otherVec).build());

        String queryVec = "[" + join(targetVec) + "]";
        List<DocumentChunk> results = chunkRepository.findTopKByCosine(
                testUser.getId(), queryVec, 0.5, 5, 40);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getId()).isEqualTo(relevant.getId());
    }

    @Test @DisplayName("findByFullTextSearch returns FTS-matched chunks")
    void ftsSearch_returnsMatchedChunks() {
        chunkRepository.save(DocumentChunk.builder()
                .document(testDoc).chunkIndex(0)
                .content("Retrieval augmented generation is a technique for improving LLM accuracy").build());
        chunkRepository.save(DocumentChunk.builder()
                .document(testDoc).chunkIndex(1)
                .content("The weather today is sunny and warm").build());

        List<DocumentChunk> results = chunkRepository.findByFullTextSearch(
                testUser.getId(), "retrieval augmented generation", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getContent()).containsIgnoringCase("retrieval");
    }

    @Test @DisplayName("Vector search respects user isolation (no cross-tenant leakage)")
    void vectorSearch_respectsUserIsolation() {
        User otherUser = userRepository.save(User.builder()
                .name("Other").email("other_" + System.nanoTime() + "@test.com")
                .password(passwordEncoder.encode("pass")).build());
        Document otherDoc = documentRepository.save(Document.builder()
                .filename("other.pdf").fileType("application/pdf")
                .uploadedBy(otherUser).ingestionStatus(Document.IngestionStatus.DONE).build());

        float[] vec = createTestVector(1.0f, 0.5f);
        chunkRepository.save(DocumentChunk.builder()
                .document(otherDoc).chunkIndex(0)
                .content("Other user private data").embeddingVec(vec).build());

        String queryVec = "[" + join(vec) + "]";
        List<DocumentChunk> results = chunkRepository.findTopKByCosine(
                testUser.getId(), queryVec, 0.0, 10, 40);

        // testUser should not see otherUser's chunks
        results.forEach(c -> assertThat(c.getDocument().getUploadedBy().getId())
                .isEqualTo(testUser.getId()));
    }

    private float[] createTestVector(float first, float second) {
        float[] vec = new float[1536];
        Arrays.fill(vec, 0.0f);
        vec[0] = first;
        if (vec.length > 1) vec[1] = second;
        // Normalize
        double norm = Math.sqrt(first * first + second * second);
        if (norm > 0) { vec[0] /= norm; if (vec.length > 1) vec[1] /= norm; }
        return vec;
    }

    private String join(float[] vec) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.toString();
    }
}

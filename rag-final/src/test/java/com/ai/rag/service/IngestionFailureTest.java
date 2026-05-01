package com.ai.rag.service;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import com.ai.rag.exception.RagProcessingException;
import com.ai.rag.metrics.RagMetrics;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.service.impl.IngestionServiceImpl;
import com.ai.rag.storage.FileStorageService;
import com.ai.rag.util.TextChunker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests ingestion failure paths.
 * FIX: These failure scenarios were untested in v1.
 */
@ExtendWith(MockitoExtension.class)
class IngestionFailureTest {

    @Mock DocumentRepository      documentRepository;
    @Mock DocumentChunkRepository chunkRepository;
    @Mock FileStorageService      fileStorageService;
    @Mock TextChunker             textChunker;
    @Mock OpenAiClient            openAiClient;
    @Mock RagMetrics              ragMetrics;

    @InjectMocks IngestionServiceImpl ingestionService;

    private User   user;
    private Document document;
    private UUID   docId;

    @BeforeEach
    void setup() {
        user = User.builder().id(UUID.randomUUID()).email("test@test.com").build();
        docId = UUID.randomUUID();
        document = Document.builder().id(docId).filename("test.pdf")
                .fileType("text/plain").uploadedBy(user)
                .ingestionStatus(Document.IngestionStatus.PENDING).build();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test @DisplayName("Document marked FAILED when S3 download fails")
    void ingest_s3DownloadFails_marksDocumentFailed() {
        when(fileStorageService.download(anyString()))
                .thenThrow(new RagProcessingException("S3 unavailable"));

        ingestionService.ingest(docId, "s3://bucket/key");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        Document saved = captor.getAllValues().stream()
                .filter(d -> d.getIngestionStatus() == Document.IngestionStatus.FAILED)
                .findFirst().orElse(null);
        assertThat(saved).isNotNull();
        assertThat(saved.getIngestionError()).contains("S3 unavailable");
        verify(ragMetrics).recordIngestionFailed();
    }

    @Test @DisplayName("Document marked FAILED when OpenAI embed fails")
    void ingest_embedFails_marksDocumentFailed() {
        String content = "Sample text content for testing";
        when(fileStorageService.download(anyString()))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        when(openAiClient.complete(anyString()))
                .thenReturn(new OpenAiClient.UsageResult("Enhanced desc", 10, 10, 20, 0.001));
        when(textChunker.split(anyString())).thenReturn(List.of("chunk1", "chunk2"));
        when(textChunker.extractKeywords(anyString())).thenReturn("keyword");
        when(openAiClient.embed(anyString()))
                .thenThrow(new RagProcessingException("OpenAI embed failed"));

        ingestionService.ingest(docId, "s3://bucket/key");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        Document failed = captor.getAllValues().stream()
                .filter(d -> d.getIngestionStatus() == Document.IngestionStatus.FAILED)
                .findFirst().orElse(null);
        assertThat(failed).isNotNull();
        verify(ragMetrics).recordIngestionFailed();
    }
}

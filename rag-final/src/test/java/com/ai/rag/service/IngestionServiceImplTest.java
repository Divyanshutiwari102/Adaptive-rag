package com.ai.rag.service;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.entity.User;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.service.impl.IngestionServiceImpl;
import com.ai.rag.util.PdfExtractor;
import com.ai.rag.util.TextChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceImplTest {

    @Mock DocumentRepository      documentRepository;
    @Mock DocumentChunkRepository chunkRepository;
    @Mock PdfExtractor            pdfExtractor;
    @Mock TextChunker             textChunker;
    @Mock OpenAiClient            openAiClient;

    @InjectMocks IngestionServiceImpl service;

    private UUID     documentId;
    private Document testDocument;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        testDocument = Document.builder()
                .id(documentId)
                .filename("test.pdf")
                .originalDescription("A test document")
                .fileType("PDF")
                .rawText("This is the raw document text for testing purposes.")
                .ingestionStatus(Document.IngestionStatus.PENDING)
                .uploadedBy(User.builder().id(UUID.randomUUID()).email("u@test.com").build())
                .build();

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Successful ingestion: status transitions PENDING → PROCESSING → DONE")
    void successfulIngestion_statusDone() {
        when(openAiClient.complete(anyString())).thenReturn("Enhanced description");
        when(textChunker.split(anyString())).thenReturn(List.of("chunk one", "chunk two"));
        when(textChunker.extractKeywords(anyString())).thenReturn("chunk one two");
        when(openAiClient.embed(anyString())).thenReturn(new float[1536]);
        when(chunkRepository.saveAll(anyList())).thenReturn(List.of());

        service.ingest(documentId);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(docCaptor.capture());

        List<Document> savedDocs = docCaptor.getAllValues();
        // Last save should be DONE
        assertThat(savedDocs.get(savedDocs.size() - 1).getIngestionStatus())
                .isEqualTo(Document.IngestionStatus.DONE);

        // totalChunks should be set
        assertThat(savedDocs.get(savedDocs.size() - 1).getTotalChunks()).isEqualTo(2);

        // rawText should be nulled after ingestion
        assertThat(savedDocs.get(savedDocs.size() - 1).getRawText()).isNull();
    }

    @Test
    @DisplayName("Chunks are persisted with native embedding vectors")
    void chunks_persistedWithEmbeddings() {
        when(openAiClient.complete(anyString())).thenReturn("Enhanced");
        when(textChunker.split(anyString())).thenReturn(List.of("chunk A", "chunk B", "chunk C"));
        when(textChunker.extractKeywords(anyString())).thenReturn("keywords");
        when(openAiClient.embed(anyString())).thenReturn(new float[1536]);

        ArgumentCaptor<List<DocumentChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        when(chunkRepository.saveAll(chunkCaptor.capture())).thenReturn(List.of());

        service.ingest(documentId);

        List<DocumentChunk> savedChunks = chunkCaptor.getValue();
        assertThat(savedChunks).isNotEmpty();
        savedChunks.forEach(c -> {
            assertThat(c.getEmbeddingVec()).isNotNull().hasSize(1536);
            assertThat(c.getDocument()).isEqualTo(testDocument);
        });
    }

    @Test
    @DisplayName("OpenAI failure sets status=FAILED with error message")
    void openAiFailure_statusFailed() {
        when(openAiClient.complete(anyString())).thenThrow(new RuntimeException("OpenAI timeout"));

        service.ingest(documentId);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(docCaptor.capture());

        Document lastSave = docCaptor.getAllValues().get(docCaptor.getAllValues().size() - 1);
        assertThat(lastSave.getIngestionStatus()).isEqualTo(Document.IngestionStatus.FAILED);
        assertThat(lastSave.getIngestionError()).contains("OpenAI timeout");
    }

    @Test
    @DisplayName("Empty raw text throws and sets status=FAILED")
    void emptyRawText_statusFailed() {
        testDocument.setRawText("   ");   // blank

        service.ingest(documentId);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(captor.capture());
        Document last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getIngestionStatus()).isEqualTo(Document.IngestionStatus.FAILED);
    }
}

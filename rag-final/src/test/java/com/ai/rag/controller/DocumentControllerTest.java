package com.ai.rag.controller;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import com.ai.rag.exception.RateLimitExceededException;
import com.ai.rag.repository.UserRepository;
import com.ai.rag.resilience.RateLimiterService;
import com.ai.rag.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for DocumentController.
 * Spring Security context is active; mocks are injected for service layer.
 */
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired MockMvc      mockMvc;
    @MockBean  DocumentService  documentService;
    @MockBean  UserRepository   userRepository;
    @MockBean  RateLimiterService rateLimiter;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .name("Test")
                .role(User.Role.USER)
                .build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(rateLimiter.tryConsume(eq("ingest"), any())).thenReturn(true);
    }

    @Test
    @WithMockUser(username = "test@example.com")
    @DisplayName("POST /ingest with valid PDF returns 202 Accepted")
    void ingest_validPdf_returns202() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf",
                "%PDF-1.4 test content".getBytes()
        );

        Document pending = Document.builder()
                .id(UUID.randomUUID())
                .filename("test.pdf")
                .fileType("PDF")
                .ingestionStatus(Document.IngestionStatus.PENDING)
                .uploadedAt(LocalDateTime.now())
                .build();

        when(documentService.acceptUpload(any(), any(), any())).thenReturn(pending);

        mockMvc.perform(multipart("/api/documents/ingest")
                        .file(file)
                        .param("description", "Test document")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestionStatus").value("PENDING"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    @DisplayName("POST /ingest when rate limited returns 429")
    void ingest_rateLimited_returns429() throws Exception {
        when(rateLimiter.tryConsume(eq("ingest"), any())).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF test".getBytes());

        mockMvc.perform(multipart("/api/documents/ingest")
                        .file(file)
                        .param("description", "desc")
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("POST /ingest without authentication returns 401/403")
    void ingest_unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF test".getBytes());

        mockMvc.perform(multipart("/api/documents/ingest")
                        .file(file)
                        .param("description", "desc")
                        .with(csrf()))
                .andExpect(status().is4xxClientError());
    }
}

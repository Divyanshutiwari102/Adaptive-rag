package com.ai.rag.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Document entity — UPDATED for S3 integration.
 *
 * CHANGE: Added `storageKey` column.
 *
 * WHY: Previously the app stored raw extracted text in `rawText` and never kept
 * a reference to the original file. With S3 integration:
 *   1. The original file is uploaded to S3 and its object key is stored here.
 *   2. During async ingestion, IngestionServiceImpl downloads the file FROM S3
 *      using this key, then extracts text on the worker thread — not the HTTP thread.
 *   3. `rawText` is no longer populated during upload; it stays null until ingestion
 *      runs (or can be removed entirely in a future cleanup).
 *   4. On DELETE, the storageKey is used to remove the S3 object.
 *
 * BACKWARD COMPATIBILITY: `storageKey` is nullable (existing rows have null).
 * Ingestion handles this gracefully — it falls back to the existing rawText path
 * if storageKey is absent. See IngestionServiceImpl for details.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_uploaded_by", columnList = "uploaded_by")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    public enum IngestionStatus { PENDING, PROCESSING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(columnDefinition = "TEXT")
    private String originalDescription;

    @Column(columnDefinition = "TEXT")
    private String enhancedDescription;

    @Column(nullable = false, length = 10)
    private String fileType;

    @Column(nullable = false)
    @Builder.Default
    private int totalChunks = 0;

    /** Tracks async ingestion lifecycle — returned to client via GET /documents/{id}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private IngestionStatus ingestionStatus = IngestionStatus.PENDING;

    /** Human-readable error if ingestion fails. */
    @Column(columnDefinition = "TEXT")
    private String ingestionError;

    /**
     * S3 object key for the original uploaded file.
     * Format: "documents/{userId}/{uuid}/{safeFilename}"
     * Null for documents ingested before S3 integration, or when storage.provider=local.
     * Used by IngestionServiceImpl to download the file for text extraction.
     * Used by deleteDocument() to clean up the S3 object.
     */
    @Column(length = 512)
    private String storageKey;

    /**
     * Temporarily stores raw extracted text for the async ingestion pipeline.
     * Populated as a fallback when storageKey is null (pre-S3 documents).
     * Nulled after ingestion completes. Not exposed via any DTO.
     */
    @Column(columnDefinition = "TEXT")
    private String rawText;

    /**
     * SHA-256 hash of file content — used for duplicate detection per user.
     * Set during upload from PdfExtractor.ExtractionResult.contentHash().
     */
    @Column(length = 64)
    private String contentHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DocumentChunk> chunks = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;
}
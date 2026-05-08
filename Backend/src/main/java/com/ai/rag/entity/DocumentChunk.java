package com.ai.rag.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One chunk of a document.
 *
 * Vector storage strategy:
 *  - embedding_vec  → native pgvector column (vector(1536)), used for ANN search
 *  - embedding_json → legacy TEXT CSV column, kept for zero-downtime backfill,
 *                     remove after all rows have been migrated.
 *
 * Cosine similarity search is now entirely in Postgres via the <=> operator
 * on an HNSW index — no Java-side loops.
 */
@Entity
@Table(name = "document_chunks", indexes = {
        @Index(name = "idx_chunks_document_id", columnList = "document_id"),
        @Index(name = "idx_chunks_chunk_index", columnList = "chunk_index")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Native pgvector column.
     * ⚠️ Hibernate ignore karega (read-only + unmanaged type)
     */
    @Column(name = "embedding_vec", columnDefinition = "vector(1536)", insertable = false, updatable = false)
    private Object embeddingVec;   // ✅ CHANGE ONLY THIS LINE

    /**
     * JSON fallback embedding — used for storage.
     */
    @Column(columnDefinition = "TEXT")
    private String embeddingJson;

    /** Pre-extracted lowercase tokens for keyword-search fallback. */
    @Column(columnDefinition = "TEXT")
    private String keywords;
}
package com.ai.rag.repository;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UPDATED — FIX #6 (Repository mismatches).
 *
 * PROBLEM 1: The previous version had existsByEmail(String email) on DocumentRepository.
 * This makes no sense — documents don't have emails. This was copied from UserRepository
 * by mistake. It causes a startup failure because Hibernate can't derive a query for
 * a non-existent "email" field on Document.
 * FIX: Removed existsByEmail from DocumentRepository. It belongs only in UserRepository.
 *
 * PROBLEM 2: DocumentServiceImpl called documentRepository.findByUploadedBy(user, pageable)
 * but the method was named findByUploadedByOrderByUploadedAtDesc. The mismatched name
 * would cause a compile error or runtime MethodNotFoundException.
 * FIX: Added findByUploadedBy(User, Pageable) as the canonical paginated query.
 * The OrderBy variant is kept for non-paginated sorted access.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * FIX #6: Paginated documents for a user — used by DocumentServiceImpl.getDocumentsForUser().
     * Pageable handles the sort — no need to embed ORDER BY in the method name.
     */
    Page<Document> findByUploadedBy(User uploadedBy, Pageable pageable);

    /** Named version kept for backward compatibility (non-paginated contexts). */
    List<Document> findByUploadedByOrderByUploadedAtDesc(User uploadedBy);

    Optional<Document> findByIdAndUploadedBy(UUID id, User uploadedBy);

    Optional<Document> findByContentHashAndUploadedBy(String contentHash, User uploadedBy);

    List<Document> findByIngestionStatus(Document.IngestionStatus status);

    // NOTE: existsByEmail intentionally NOT here — it belongs in UserRepository only.

    /**
     * Atomic compare-and-swap: transitions PENDING → PROCESSING.
     * Returns 1 if this call claimed the document; 0 if already claimed by another worker.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE documents
               SET ingestion_status = 'PROCESSING'
             WHERE id = :id
               AND ingestion_status = 'PENDING'
            """, nativeQuery = true)
    int claimForProcessing(@Param("id") UUID id);
}
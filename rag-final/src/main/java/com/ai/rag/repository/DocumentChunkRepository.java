package com.ai.rag.repository;

import com.ai.rag.entity.DocumentChunk;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * pgvector HNSW cosine similarity search — filtered by tenant (uploaded_by).
     *
     * FIX #3 (RLS): The WHERE d.uploaded_by = :userId clause is the application-layer
     * tenant filter. V7 RLS migration adds DB-level enforcement as a second layer.
     * Both together mean a filter bug in one layer is caught by the other.
     *
     * NOTE: SET LOCAL hnsw.ef_search has been removed from this query.
     * Multi-statement native queries break with Hibernate's PreparedStatement.
     * ef_search is set per-connection in VectorSearchRepositoryImpl using
     * Session#doWork before this query executes (when ef_search tuning is needed).
     * For most deployments, the default ef_search=40 is sufficient.
     */
    @Query(value = """
            SELECT dc.*
            FROM document_chunks dc
            JOIN documents d ON dc.document_id = d.id
            WHERE d.uploaded_by = :userId
              AND dc.embedding_vec IS NOT NULL
              AND 1 - (dc.embedding_vec <=> CAST(:queryVec AS vector)) >= :threshold
            ORDER BY dc.embedding_vec <=> CAST(:queryVec AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> findTopKByCosine(
            @Param("userId")    UUID   userId,
            @Param("queryVec")  String queryVec,
            @Param("threshold") double threshold,
            @Param("topK")      int    topK);

    /**
     * FTS using stored tsvector column (GIN-indexed).
     * FIX #3 (RLS): Same tenant filter + DB-level RLS as second layer.
     */
    @Query(value = """
            SELECT dc.*
            FROM document_chunks dc
            JOIN documents d ON dc.document_id = d.id
            WHERE d.uploaded_by = :userId
              AND dc.content_tsv @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(dc.content_tsv, plainto_tsquery('english', :query)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> findByFullTextSearch(
            @Param("userId") UUID   userId,
            @Param("query")  String query,
            @Param("topK")   int    topK);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    long countByDocumentId(UUID documentId);

    @Modifying
    @Transactional
    @Query("DELETE FROM DocumentChunk dc WHERE dc.document.id = :docId")
    void deleteByDocumentId(@Param("docId") UUID docId);
}

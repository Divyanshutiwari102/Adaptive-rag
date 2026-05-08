package com.ai.rag.service;

import java.util.UUID;

/**
 * Exists solely so that the @Async ingestion method lives in a different
 * Spring bean from DocumentServiceImpl.
 *
 * Self-invocation of @Async within the same bean bypasses the proxy and
 * runs synchronously — a classic Spring pitfall. This interface and its
 * implementation (IngestionServiceImpl) avoid that entirely.
 */
public interface IngestionService {

    /**
     * Runs the full ingestion pipeline for a previously-persisted Document.
     * Called asynchronously — the caller (DocumentServiceImpl) does not wait.
     *
     * Lifecycle:
     *   Document.ingestionStatus: PENDING → PROCESSING → DONE | FAILED
     */
    void ingest(UUID documentId);
}

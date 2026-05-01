package com.ai.rag.service.impl;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.DocumentChunk;
import com.ai.rag.repository.DocumentChunkRepository;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.service.IngestionService;
import com.ai.rag.service.OpenAiClient;
import com.ai.rag.storage.FileStorageService;
import com.ai.rag.util.PdfExtractor;
import com.ai.rag.util.TextChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * IngestionServiceImpl — UPDATED for S3 integration.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE CHANGE: Read file FROM S3, not from DB rawText
 * ══════════════════════════════════════════════════════════════════════════
 *
 * BEFORE:
 *   ingest(id)
 *     → load Document from DB
 *     → read rawText from Document.getRawText()   ← text stored in DB column
 *     → chunk + embed + store
 *
 * AFTER:
 *   ingest(id)
 *     → load Document from DB
 *     → IF storageKey != null:
 *         → download file bytes from S3 using storageKey
 *         → extract text via PdfExtractor (worker thread — correct)
 *     → ELSE (backward-compat fallback):
 *         → use rawText from DB (pre-S3 documents)
 *     → chunk + embed + store
 *     → null out rawText (cleanup)
 *
 * WHY TEXT EXTRACTION BELONGS HERE, NOT IN DocumentServiceImpl:
 *   Text extraction (PdfExtractor) is CPU-bound and can be slow for large PDFs.
 *   Running it on the HTTP request thread (in DocumentServiceImpl) adds latency
 *   to the upload response. With S3: the HTTP thread just uploads bytes and
 *   returns PENDING. The async worker thread does the heavy lifting.
 *
 * NO "STREAM CLOSED" ISSUE:
 *   We download from S3 into a byte[] immediately (is.readAllBytes()).
 *   This closes the S3 HTTP connection promptly and gives us a stable in-memory
 *   buffer for all subsequent processing. The @Async worker thread owns this
 *   byte[] entirely — no multipart lifecycle, no Tomcat temp-file, no contest
 *   with @Transactional proxies.
 *
 * ASYNC PROCESSING CORRECTNESS:
 *   The @Async method ingest() calls self.markProcessingAtomic() which runs in
 *   its own REQUIRES_NEW transaction — this prevents the atomic claim from being
 *   rolled back if the outer async task later fails. Pattern unchanged from before.
 *
 * BACKWARD COMPATIBILITY:
 *   Documents uploaded before this change have storageKey=null and rawText set.
 *   The fallback branch (rawText != null) handles these transparently.
 * ══════════════════════════════════════════════════════════════════════════
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionServiceImpl implements IngestionService {

    private final DocumentRepository      documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final TextChunker             textChunker;
    private final OpenAiClient            openAiClient;
    private final FileStorageService      fileStorageService;   // ← NEW: S3 download
    private final PdfExtractor            pdfExtractor;         // ← NEW: extract on worker thread

    @Autowired
    @Lazy
    private IngestionServiceImpl self;

    private static final int BATCH_SIZE = 50;

    // ── Main async ingestion entry point ──────────────────────────────────────

    @Override
    @Async("ingestionExecutor")
    public void ingest(UUID documentId) {
        log.info("[Ingest] Starting doc={}", documentId);

        // Atomic claim — prevents duplicate processing if ingest() is called twice
        int claimed = self.markProcessingAtomic(documentId);
        if (claimed == 0) {
            log.warn("[Ingest] doc={} already claimed, skipping", documentId);
            return;
        }

        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.error("[Ingest] doc={} not found in DB", documentId);
            return;
        }

        try {
            // ── Step 1: Resolve raw text ────────────────────────────────────
            String rawText = resolveRawText(document);

            if (rawText == null || rawText.isBlank()) {
                throw new IllegalStateException(
                        "Could not resolve raw text for doc=" + documentId
                                + " (storageKey=" + document.getStorageKey()
                                + ", rawText=" + (document.getRawText() != null ? "present" : "null") + ")");
            }

            // ── Step 2: Enhance description (optional, non-blocking) ────────
            String enhanced = null;
            try {
                enhanced = openAiClient.complete(
                        "Rewrite as retriever instruction:\n" + document.getOriginalDescription()
                ).text();
            } catch (Exception e) {
                log.warn("[Ingest] description enhancement failed: {}", e.getMessage());
            }

            // ── Step 3: Chunk + embed + persist ─────────────────────────────
            List<String> chunks = textChunker.split(rawText);
            log.info("[Ingest] doc={} split into {} chunks", documentId, chunks.size());

            List<DocumentChunk> batch = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);

                float[] embedding = openAiClient.embed(chunk);
                String keywords = textChunker.extractKeywords(chunk);

                batch.add(DocumentChunk.builder()
                        .document(document)
                        .chunkIndex(i)
                        .content(chunk)
                        .embeddingJson(Arrays.toString(embedding))
                        .keywords(keywords)
                        .build());

                if (batch.size() == BATCH_SIZE) {
                    chunkRepository.saveAll(batch);
                    batch.clear();
                    log.debug("[Ingest] doc={} flushed batch at chunk {}", documentId, i);
                }
            }

            if (!batch.isEmpty()) {
                chunkRepository.saveAll(batch);
            }

            // ── Step 4: Mark DONE ────────────────────────────────────────────
            // rawText is nulled in markDone() — clears any leftover column value.
            self.markDone(documentId, chunks.size(), enhanced);
            log.info("[Ingest] doc={} DONE ({} chunks)", documentId, chunks.size());

        } catch (Exception e) {
            log.error("[Ingest] FAILED doc={}: {}", documentId, e.getMessage(), e);
            self.markFailed(documentId, e.getMessage());
        }
    }

    // ── Text resolution: S3 path or rawText fallback ──────────────────────────

    /**
     * Resolve raw text for ingestion.
     *
     * PRIMARY PATH (S3): storageKey is set → download file from S3 → extract text.
     *   - This is the path for all documents uploaded after S3 integration.
     *   - Text extraction runs on the async worker thread (CPU-bound work belongs here).
     *   - The S3 InputStream is read fully into a byte[] and closed promptly.
     *
     * FALLBACK PATH (legacy): storageKey is null → use rawText from DB.
     *   - This handles documents uploaded before S3 integration.
     *   - rawText may be null for these too (e.g. if ingestion was partially completed).
     *     In that case the caller will throw an appropriate error.
     *
     * @param document the Document entity (loaded from DB)
     * @return extracted text, or null if neither source is available
     */
    private String resolveRawText(Document document) throws IOException {
        String storageKey = document.getStorageKey();

        if (storageKey != null && !storageKey.isBlank()) {
            // S3 path: download → extract on worker thread
            log.info("[Ingest] Downloading from S3 key={} doc={}", storageKey, document.getId());
            byte[] fileBytes;

            try (InputStream is = fileStorageService.download(storageKey)) {
                // Read all bytes immediately so the S3 HTTP connection is released.
                // For files up to 50 MB (our enforced limit), this is safe in heap.
                fileBytes = is.readAllBytes();
            }
            // Re-use PdfExtractor on the worker thread — correct, because:
            //   a) CPU-bound work belongs on async threads, not HTTP threads
            //   b) We have stable bytes (no stream lifecycle issues)
            //   c) contentType stored in fileType column (set during upload)
            PdfExtractor.ExtractionResult result =
                    pdfExtractor.extract(fileBytes, document.getFileType());
            log.info("[Ingest] Extracted {} chars from S3 doc={}", result.text().length(), document.getId());
            return result.text();

        } else if (document.getRawText() != null && !document.getRawText().isBlank()) {
            // Legacy fallback path — pre-S3 documents
            log.info("[Ingest] Using DB rawText fallback for doc={}", document.getId());
            return document.getRawText();

        } else {
            // Neither source available — will cause ingestion to fail with clear message
            log.warn("[Ingest] No text source for doc={} (storageKey=null, rawText=null)", document.getId());
            return null;
        }
    }

    // ── State transitions (each in its own transaction) ───────────────────────

    /**
     * Atomically claim the document for processing.
     * Uses REQUIRES_NEW so this commit is independent of the outer async task.
     * If the outer task fails and rolls back, this claim is NOT rolled back —
     * preventing another thread from re-claiming and double-processing.
     *
     * @return 1 if claimed, 0 if already claimed by another thread
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markProcessingAtomic(UUID documentId) {
        return documentRepository.claimForProcessing(documentId);
    }

    /**
     * Mark ingestion as DONE and clear the rawText column.
     * Clearing rawText is important for storage efficiency — the text can be
     * large (up to several MB) and is no longer needed after chunking.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(UUID documentId, int chunkCount, String enhanced) {
        documentRepository.findById(documentId).ifPresent(d -> {
            d.setIngestionStatus(Document.IngestionStatus.DONE);
            d.setTotalChunks(chunkCount);
            d.setRawText(null);   // ← always null after ingestion (cleanup)
            if (enhanced != null) d.setEnhancedDescription(enhanced);
            documentRepository.save(d);
        });
    }

    /**
     * Mark ingestion as FAILED with an error message.
     * rawText is preserved here in case a re-try is needed (manually via
     * runIngestionAsync). A future enhancement could add a retry mechanism.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, String errorMessage) {
        documentRepository.findById(documentId).ifPresent(d -> {
            d.setIngestionStatus(Document.IngestionStatus.FAILED);
            d.setIngestionError(errorMessage != null
                    ? errorMessage.substring(0, Math.min(errorMessage.length(), 1000))
                    : "Unknown error");
            documentRepository.save(d);
        });
    }
}
package com.ai.rag.service.impl;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import com.ai.rag.exception.RagProcessingException;
import com.ai.rag.exception.ResourceNotFoundException;
import com.ai.rag.repository.DocumentRepository;
import com.ai.rag.service.DocumentService;
import com.ai.rag.service.IngestionService;
import com.ai.rag.storage.FileStorageService;
import com.ai.rag.util.PdfExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.UUID;

/**
 * DocumentServiceImpl — UPDATED for S3 integration.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE CHANGE: File Storage via S3
 * ══════════════════════════════════════════════════════════════════════════
 *
 * BEFORE (broken pipeline):
 *   Controller → [byte[]] → DocumentServiceImpl
 *     → PdfExtractor.extract(bytes)       ← text extraction on HTTP thread
 *     → document.setRawText(text)         ← text stored in DB (up to MBs)
 *     → ingestionService.ingest(id)       ← async picks up rawText from DB
 *
 * PROBLEMS WITH THE OLD APPROACH:
 *   1. Large text blobs in the DB column bloat the documents table.
 *   2. The raw file is discarded — no original stored for re-processing.
 *   3. S3FileStorageService existed but was never called — completely unused.
 *   4. DB reads rawText column on every ingestion fetch, wasting I/O.
 *
 * AFTER (correct S3-first pipeline):
 *   Controller → [byte[]] → DocumentServiceImpl
 *     → PdfExtractor.extract(bytes)       ← still needed for contentHash + MIME
 *     → fileStorageService.upload(bytes)  ← file goes to S3, key returned
 *     → document.setStorageKey(key)       ← ONLY the key stored in DB (short string)
 *     → document.setRawText(null)         ← NOT stored in DB anymore
 *     → ingestionService.ingest(id)       ← async downloads from S3, extracts text
 *
 * WHY KEEP PdfExtractor HERE:
 *   We still call PdfExtractor.extract() during upload for two reasons:
 *   a) Compute contentHash for duplicate detection (same as before).
 *   b) Validate the file is parseable before queuing it — fail fast.
 *   The extracted text itself is NOT stored; we let IngestionServiceImpl
 *   re-extract from S3 on the worker thread where it belongs.
 *
 * WHY NO "STREAM CLOSED" ISSUE:
 *   DocumentController calls file.getBytes() eagerly (the existing fix).
 *   DocumentServiceImpl receives a stable byte[]. We pass the same byte[]
 *   to both PdfExtractor and fileStorageService.upload() — no streams,
 *   no lifecycle races, no @Transactional proxy issues.
 *
 * DELETE BEHAVIOR:
 *   deleteDocument() now also deletes the S3 object via storageKey.
 *   If storageKey is null (pre-S3 documents), the S3 delete is skipped safely.
 *
 * BACKWARD COMPATIBILITY:
 *   Documents created before this change have storageKey=null and rawText
 *   populated. IngestionServiceImpl handles the null-storageKey fallback path
 *   (uses rawText from DB if present, as before).
 * ══════════════════════════════════════════════════════════════════════════
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final PdfExtractor        pdfExtractor;
    private final IngestionService    ingestionService;
    private final FileStorageService  fileStorageService;   // ← NEW: S3 or local

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024; // 50 MB

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Accept an uploaded file, store it to S3, persist metadata, and queue ingestion.
     *
     * FLOW:
     *   1. Validate size + sanitize filename.
     *   2. Call PdfExtractor to validate content and compute hash (fast, in-memory).
     *   3. Upload bytes to S3 → get storageKey.
     *   4. Persist Document with storageKey (no rawText in DB).
     *   5. Trigger async ingestion (downloads from S3 on worker thread).
     *
     * @param fileBytes        eagerly-read bytes from the multipart upload
     * @param originalFilename raw filename from Content-Disposition (sanitized here)
     * @param contentType      MIME type from the request
     * @param description      user-supplied description of the document
     * @param uploader         authenticated user
     * @return                 persisted Document entity (status = PENDING)
     */
    @Override
    @Transactional
    public Document acceptUpload(byte[] fileBytes,
                                 String originalFilename,
                                 String contentType,
                                 String description,
                                 User uploader) {

        // ── Step 1: Validate ───────────────────────────────────────────────
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("File must not be empty");
        }
        if (fileBytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File exceeds 50 MB limit");
        }

        String safeFilename = sanitizeFilename(originalFilename);

        // ── Step 2: Extract metadata (hash + MIME validation) ──────────────
        // We call extract() here ONLY to:
        //   a) Validate the file is a real PDF/text (fail fast before S3 upload)
        //   b) Compute contentHash for duplicate detection
        // The extracted text is NOT stored — IngestionServiceImpl re-extracts from S3.
        PdfExtractor.ExtractionResult extraction = pdfExtractor.extract(fileBytes, contentType);

        if (extraction.text().isBlank()) {
            throw new RagProcessingException(
                    "File appears empty or could not be parsed: " + safeFilename);
        }

        // ── Step 3: Upload to S3 ───────────────────────────────────────────
        // Key prefix: "documents/{userId}" — groups files by owner in S3.
        String keyPrefix = "documents/" + uploader.getId();
        String storageKey = fileStorageService.upload(fileBytes, keyPrefix, safeFilename, contentType);

        log.info("[Upload] S3 upload complete key={} user={}", storageKey, uploader.getEmail());

        // ── Step 4: Persist Document metadata ─────────────────────────────
        // IMPORTANT: rawText is NOT set here. The ingestion worker reads from S3.
        // storageKey is the only reference we keep to the file bytes.
        Document document = Document.builder()
                .filename(safeFilename)
                .originalDescription(description)
                .fileType(extraction.mimeType())
                .uploadedBy(uploader)
                .storageKey(storageKey)          // ← NEW: S3 reference
                .rawText(null)                   // ← explicitly null (no DB bloat)
                .contentHash(extraction.contentHash())
                .ingestionStatus(Document.IngestionStatus.PENDING)
                .build();

        documentRepository.save(document);

        log.info("[Upload] Accepted doc={} file={} user={}",
                document.getId(), safeFilename, uploader.getEmail());

        // ── Step 5: Trigger async ingestion ───────────────────────────────
        // IngestionServiceImpl will:
        //   a) Download the file from S3 using storageKey
        //   b) Extract text (PdfExtractor on the worker thread)
        //   c) Chunk + embed + store vectors
        //   d) Set status = DONE (or FAILED)
        ingestionService.ingest(document.getId());

        return document;
    }

    // ── Re-trigger ingestion ──────────────────────────────────────────────────

    @Override
    public void runIngestionAsync(UUID documentId) {
        ingestionService.ingest(documentId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<Document> getDocumentsForUser(User user, Pageable pageable) {
        return documentRepository.findByUploadedBy(user, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Document getDocumentById(UUID id, User user) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document", id));
        if (!doc.getUploadedBy().getId().equals(user.getId()))
            throw new ResourceNotFoundException("Document", id);
        return doc;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Delete document metadata from DB and the original file from S3.
     *
     * ORDER: S3 delete first, then DB delete.
     * Rationale: If the DB delete fails (e.g. FK constraint), the S3 object
     * is gone but the DB record survives — a re-try of the delete will attempt
     * S3 delete again (idempotent, safe). The reverse order (DB first) could
     * leave orphaned S3 objects if the DB succeeds but S3 fails.
     *
     * NOTE: S3 delete is best-effort (S3FileStorageService.delete() logs but
     * doesn't throw). The DB delete proceeds regardless of S3 outcome.
     */
    @Override
    @Transactional
    public void deleteDocument(UUID id, User user) {
        Document doc = getDocumentById(id, user);

        // Clean up S3 object if present (null-safe for pre-S3 documents)
        if (doc.getStorageKey() != null) {
            fileStorageService.delete(doc.getStorageKey());
            log.info("[Delete] S3 object removed key={}", doc.getStorageKey());
        }

        documentRepository.delete(doc);
        log.info("[Delete] doc={} deleted by user={}", id, user.getEmail());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Strip all directory components from the filename.
     * Paths.get(name).getFileName() handles:
     *   "../../etc/passwd"       → "passwd"
     *   "C:\\Windows\\evil.exe"  → "evil.exe"
     *   "normal.pdf"             → "normal.pdf"
     * Null bytes cause InvalidPathException → replaced with "unknown".
     */
    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "unknown";
        try {
            String safe = Paths.get(original).getFileName().toString();
            return safe.isBlank() ? "unknown" : safe;
        } catch (Exception e) {
            log.warn("[Upload] Could not sanitize filename '{}': {}", original, e.getMessage());
            return "unknown";
        }
    }

    // validateFileSize(MultipartFile) kept for compatibility — not used in main flow
    private void validateFileSize(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("File must not be empty");
        if (file.getSize() > MAX_FILE_BYTES)
            throw new IllegalArgumentException("File exceeds 50 MB limit");
    }
}
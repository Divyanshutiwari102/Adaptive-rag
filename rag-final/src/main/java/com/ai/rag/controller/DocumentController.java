package com.ai.rag.controller;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import com.ai.rag.exception.RateLimitExceededException;
import com.ai.rag.repository.UserRepository;
import com.ai.rag.resilience.RateLimiterService;
import com.ai.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.MediaType;

/**
 * Document Controller — Production-Grade
 *
 * KEY FIX vs. the original:
 *
 * resolveUser() now guards against null UserDetails before calling getUsername().
 *
 * ROOT CAUSE of NPE: SecurityConfig had /api/** as permitAll(), meaning
 * unauthenticated requests reached this controller with null UserDetails.
 * Even after that root bug is fixed in SecurityConfig, defensive null-checking
 * here is correct practice — it provides a clear error message instead of a
 * cryptic NullPointerException in logs.
 *
 * The primary fix is in SecurityConfig (.authenticated() not .permitAll()).
 * This null-guard is a defence-in-depth safety net.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService    documentService;
    private final UserRepository     userRepository;
    private final RateLimiterService rateLimiter;

    /**
     * POST /api/documents/ingest
     * Accepts a multipart file upload and queues it for async ingestion.
     * Requires: multipart/form-data (use form-data in Postman, NOT raw JSON).
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam("description") String description,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);

        if (!rateLimiter.tryConsume("ingest", user.getId().toString())) {
            throw new RateLimitExceededException(60L);
        }

        final byte[] fileBytes;
        try {
            fileBytes = file.getBytes(); // 🔥 ROOT FIX
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
        }

        Document doc = documentService.acceptUpload(
                fileBytes,
                file.getOriginalFilename(),
                file.getContentType(),
                description,
                user
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getFileType(),
                doc.getIngestionStatus().name(),
                doc.getUploadedAt(),
                "Ingestion queued. Poll GET /api/documents/" + doc.getId()
        ));
    }

    /**
     * GET /api/documents
     * Paginated list of the authenticated user's documents.
     */
    @GetMapping
    public ResponseEntity<Page<DocumentSummary>> listDocuments(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        Page<Document> docs = documentService.getDocumentsForUser(
                user, PageRequest.of(page, size, Sort.by("uploadedAt").descending()));

        return ResponseEntity.ok(docs.map(DocumentSummary::from));
    }

    /**
     * GET /api/documents/{id}
     * Returns document metadata including ingestion_status.
     * Poll this endpoint after POST /ingest until status = DONE or FAILED.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentSummary> getDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        return ResponseEntity.ok(DocumentSummary.from(documentService.getDocumentById(id, user)));
    }

    /**
     * DELETE /api/documents/{id}
     * Deletes document and all associated chunks (cascade).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = resolveUser(userDetails);
        documentService.deleteDocument(id, user);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the authenticated UserDetails to a User entity.
     *
     * NULL GUARD: userDetails should never be null here because SecurityConfig
     * requires authentication for /api/**. The null check is a defence-in-depth
     * measure that provides a clear 500 message instead of a cryptic NPE if
     * the security config is ever misconfigured again.
     */
    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) {
            // This path should be unreachable after the SecurityConfig fix.
            // If it IS reached, it means a security misconfiguration — log loudly.
            throw new IllegalStateException(
                    "UserDetails is null — check SecurityConfig. /api/** must require authentication.");
        }
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user '" + userDetails.getUsername() + "' not found in DB. "
                                + "User may have been deleted after token was issued."));
    }

    // ── Response types ────────────────────────────────────────────────────────

    public record IngestResponse(
            UUID id,
            String filename,
            String fileType,
            String ingestionStatus,
            LocalDateTime uploadedAt,
            String message
    ) {}

    public record DocumentSummary(
            UUID id,
            String filename,
            String fileType,
            int totalChunks,
            String ingestionStatus,
            String ingestionError,
            String originalDescription,
            LocalDateTime uploadedAt
    ) {
        public static DocumentSummary from(Document doc) {
            return new DocumentSummary(
                    doc.getId(),
                    doc.getFilename(),
                    doc.getFileType(),
                    doc.getTotalChunks(),
                    doc.getIngestionStatus().name(),
                    doc.getIngestionError(),
                    doc.getOriginalDescription(),
                    doc.getUploadedAt()
            );
        }
    }
}
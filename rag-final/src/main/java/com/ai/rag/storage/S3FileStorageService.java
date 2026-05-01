package com.ai.rag.storage;

import com.ai.rag.config.StorageProperties;
import com.ai.rag.exception.RagProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * S3-backed file storage — used in production.
 *
 * UPDATED for S3 integration task:
 *  - Added upload(byte[], keyPrefix, filename, contentType) — the primary path
 *    used by DocumentServiceImpl. Accepts stable in-memory bytes; avoids any
 *    stream lifecycle issues with MultipartFile.
 *  - The MultipartFile overload now delegates to the byte[] overload for DRY
 *    implementation (reads bytes once, then calls the core method).
 *  - generatePresignedUploadUrl() added for BONUS presigned-URL upload flow.
 *
 * THREAD SAFETY: S3Client is thread-safe and shared. S3Presigner is also
 * thread-safe. Both are Spring singletons.
 *
 * KEY FORMAT: "{keyPrefix}/{uuid}/{safeFilename}"
 *   e.g. "documents/a1b2c3/550e8400-e29b-41d4/report.pdf"
 * The UUID segment prevents key collisions when the same filename is uploaded
 * multiple times by the same user.
 */
@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "s3", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class S3FileStorageService implements FileStorageService {

    private final S3Client           s3Client;
    private final StorageProperties  storageProperties;

    // S3Presigner is optional — only used by generatePresignedUploadUrl().
    // It is injected lazily via @Lazy if present, or can be added to S3Config.
    // Declared here for the BONUS presigned-URL feature; remove if not needed.
    // private final S3Presigner s3Presigner;  // ← uncomment after adding to S3Config

    // ── Primary upload path (byte[]) ─────────────────────────────────────────

    /**
     * Upload raw bytes to S3 and return the object key.
     *
     * WHY byte[] instead of InputStream: DocumentController eagerly reads
     * file.getBytes() to avoid stream-closed races with @Transactional +
     * Tomcat temp-file cleanup. By the time we reach here the bytes are stable
     * in memory — wrapping them in a ByteArrayInputStream is safe and zero-copy.
     *
     * SERVER-SIDE ENCRYPTION: AES256 is applied on every object at rest.
     * This satisfies most compliance requirements at no extra cost.
     */
    @Override
    public String upload(byte[] fileBytes, String keyPrefix, String filename, String contentType) {
        String key = buildKey(keyPrefix, filename);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.getS3().getBucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) fileBytes.length)
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .build(),
                    RequestBody.fromBytes(fileBytes)   // no stream lifecycle issues
            );
            log.info("[S3] Uploaded key={} size={}B", key, fileBytes.length);
            return key;
        } catch (S3Exception e) {
            throw new RagProcessingException("Failed to upload file to S3: " + e.getMessage(), e);
        }
    }

    // ── Backward-compatible MultipartFile overload ───────────────────────────

    /**
     * Upload a MultipartFile by reading its bytes and delegating to the
     * byte[] overload. This keeps the interface backward-compatible for
     * any callers that still have a MultipartFile reference.
     */
    @Override
    public String upload(MultipartFile file, String keyPrefix) {
        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "unknown";
            String contentType = file.getContentType() != null
                    ? file.getContentType() : "application/octet-stream";
            return upload(bytes, keyPrefix, filename, contentType);
        } catch (IOException e) {
            throw new RagProcessingException("Failed to read MultipartFile for S3 upload: " + e.getMessage(), e);
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Stream an S3 object back as an InputStream.
     *
     * IMPORTANT: The returned stream is backed by the S3 HTTP connection.
     * The caller MUST close it (use try-with-resources). Failure to close
     * leaks a TCP connection from the SDK's HTTP connection pool.
     *
     * For small files (< a few MB) consider reading to byte[] immediately:
     *   try (InputStream is = storageService.download(key)) {
     *       byte[] bytes = is.readAllBytes();
     *   }
     */
    @Override
    public InputStream download(String storageKey) {
        try {
            return s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(storageProperties.getS3().getBucket())
                            .key(storageKey)
                            .build()
            );
        } catch (NoSuchKeyException e) {
            throw new RagProcessingException("S3 object not found: " + storageKey, e);
        } catch (S3Exception e) {
            throw new RagProcessingException("Failed to download from S3: " + storageKey, e);
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Delete an S3 object. Best-effort — logs a warning but does not throw
     * if the object is missing (idempotent delete is safe).
     */
    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(storageProperties.getS3().getBucket())
                            .key(storageKey)
                            .build()
            );
            log.info("[S3] Deleted key={}", storageKey);
        } catch (S3Exception e) {
            log.warn("[S3] Could not delete key={}: {}", storageKey, e.getMessage());
        }
    }

    // ── BONUS: Presigned upload URL ──────────────────────────────────────────
    // Uncomment and inject S3Presigner in the constructor to enable this.
    //
    // Generates a time-limited URL the browser/client can PUT directly to S3.
    // This removes the file bytes from your application server entirely,
    // saving bandwidth and compute.
    //
    // Flow:
    //   1. Client calls POST /api/documents/presign?filename=report.pdf
    //   2. Server returns { presignedUrl, storageKey }
    //   3. Client PUTs the file directly to presignedUrl (no auth header needed)
    //   4. Client calls POST /api/documents/ingest with { storageKey, description }
    //      (no file bytes in this call — just the key)
    //
    // public PresignedUploadResult generatePresignedUploadUrl(
    //         String userId, String filename, String contentType, Duration expiry) {
    //
    //     String key = buildKey("documents/" + userId, filename);
    //     PutObjectPresignRequest req = PutObjectPresignRequest.builder()
    //             .signatureDuration(expiry)
    //             .putObjectRequest(r -> r
    //                     .bucket(storageProperties.getS3().getBucket())
    //                     .key(key)
    //                     .contentType(contentType)
    //                     .serverSideEncryption(ServerSideEncryption.AES256))
    //             .build();
    //
    //     String url = s3Presigner.presignPutObject(req).url().toString();
    //     return new PresignedUploadResult(url, key);
    // }
    //
    // public record PresignedUploadResult(String presignedUrl, String storageKey) {}

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Build a collision-resistant S3 object key.
     * Pattern: "{keyPrefix}/{uuid}/{filename}"
     * Example: "documents/user-abc123/550e8400-e29b/report.pdf"
     */
    private String buildKey(String keyPrefix, String filename) {
        return keyPrefix + "/" + UUID.randomUUID() + "/" + filename;
    }
}
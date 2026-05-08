package com.ai.rag.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

/**
 * Abstraction over file blob storage (S3 in production, local in dev).
 *
 * UPDATED: Added upload(byte[], String, String, String, long) overload.
 *
 * WHY: DocumentController eagerly reads file bytes (file.getBytes()) before
 * passing them to DocumentServiceImpl. This prevents "Stream closed" errors.
 * The service therefore has byte[] — not MultipartFile — by the time it needs
 * to call storage. Adding a byte[]-based upload method lets us pass the stable
 * in-memory bytes directly to S3 without re-opening any stream.
 *
 * The MultipartFile overload is kept for backward compatibility with any code
 * that still calls it directly (e.g. tests). Both overloads are functionally
 * equivalent; implementors may delegate one to the other.
 */
public interface FileStorageService {

    /**
     * Upload raw bytes and return the S3 object key.
     *
     * @param fileBytes    the file content
     * @param keyPrefix    path prefix, e.g. "documents/{userId}"
     * @param filename     the sanitized original filename (for the key suffix)
     * @param contentType  MIME type for the S3 Content-Type header
     * @return             the storage key used to retrieve or delete the object
     */
    String upload(byte[] fileBytes, String keyPrefix, String filename, String contentType);

    /**
     * Upload a MultipartFile and return the storage key.
     * Kept for backward compatibility. Prefer the byte[] overload when bytes
     * have already been eagerly read.
     */
    String upload(MultipartFile file, String keyPrefix);

    /**
     * Open an InputStream to a stored object.
     * Caller is responsible for closing the stream.
     */
    InputStream download(String storageKey);

    /**
     * Delete a stored object (best-effort — does not throw on missing key).
     */
    void delete(String storageKey);
}
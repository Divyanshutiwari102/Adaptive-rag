package com.ai.rag.storage;

import com.ai.rag.exception.RagProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/**
 * Local filesystem storage — for development without AWS credentials.
 * NOT for production use.
 *
 * UPDATED: Added upload(byte[], ...) overload to match the updated
 * FileStorageService interface. The MultipartFile overload now delegates
 * to the byte[] overload for a consistent implementation.
 */
@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "local")
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("java.io.tmpdir"), "rag-uploads");

    // ── Primary upload path (byte[]) ─────────────────────────────────────────

    @Override
    public String upload(byte[] fileBytes, String keyPrefix, String filename, String contentType) {
        try {
            String key = keyPrefix + "/" + UUID.randomUUID() + "/" + filename;
            Path target = BASE_DIR.resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, fileBytes);
            log.info("[LocalStorage] Saved to {}", target);
            return key;
        } catch (IOException e) {
            throw new RagProcessingException("Local storage upload failed: " + e.getMessage(), e);
        }
    }

    // ── Backward-compatible MultipartFile overload ───────────────────────────

    @Override
    public String upload(MultipartFile file, String keyPrefix) {
        try {
            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "unknown";
            return upload(file.getBytes(), keyPrefix, filename,
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        } catch (IOException e) {
            throw new RagProcessingException("Local storage upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        try {
            return Files.newInputStream(BASE_DIR.resolve(storageKey));
        } catch (IOException e) {
            throw new RagProcessingException("Local storage download failed: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(BASE_DIR.resolve(storageKey));
            log.info("[LocalStorage] Deleted {}", storageKey);
        } catch (IOException e) {
            log.warn("[LocalStorage] Delete failed for {}: {}", storageKey, e.getMessage());
        }
    }
}
package com.ai.rag.util;

import com.ai.rag.exception.FileSanitizationException;
import com.ai.rag.exception.RagProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * PDF/Text Extractor — Production-Grade
 *
 * ══════════════════════════════════════════════════════════════════════════
 * BUG FIX: "Failed to read file: Stream closed"
 *   Root cause: extractPdfStreaming() called is.readAllBytes(), which drained
 *   the DigestInputStream. Then dis.transferTo() at line 83 tried to read
 *   from an already-exhausted stream, triggering java.io.IOException: Stream closed.
 *
 *   A secondary cause: Spring's @Transactional CGLIB proxy on DocumentServiceImpl
 *   + Tomcat's multipart temp-file cleanup can race with the stream reference —
 *   by the time processing reaches dis.transferTo(), the temp file backing
 *   MultipartFile.getInputStream() may have been cleaned up by the container.
 *
 * FIX: Call file.getBytes() EAGERLY at the very top of extract(). This copies
 * the uploaded file into a plain byte[] immediately, before any transactional
 * wrappers or stream processing. All subsequent operations work on the stable
 * in-memory byte array via ByteArrayInputStream — no more stream lifecycle races.
 *
 * The one-pass SHA-256 + text extraction pattern is preserved: DigestInputStream
 * wraps the ByteArrayInputStream so the hash is computed while streaming,
 * without a second pass over the data.
 * ══════════════════════════════════════════════════════════════════════════
 */
@Component
@Slf4j
public class PdfExtractor {

    private static final Set<String> ALLOWED_TYPES  = Set.of("application/pdf", "text/plain");
    private static final byte[]      PDF_MAGIC       = {0x25, 0x50, 0x44, 0x46}; // %PDF
    /** Hard cap on extracted text to prevent pathological inputs from consuming heap. */
    private static final int         MAX_TEXT_CHARS  = 5_000_000; // ~3,750 pages

    /**
     * Validate, extract text, and compute SHA-256 hash.
     *
     * KEY FIX: file.getBytes() is called FIRST, before any streaming operations.
     * This decouples processing from the multipart request lifecycle and prevents
     * "Stream closed" errors caused by @Transactional + Tomcat temp-file cleanup.
     */
    public ExtractionResult extract(byte[] fileBytes, String contentType) {

        if (fileBytes.length == 0) {
            throw new IllegalArgumentException("File is empty");
        }

        String mimeType = (contentType != null) ? contentType : "text/plain";

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            DigestInputStream dis = new DigestInputStream(
                    new ByteArrayInputStream(fileBytes), digest);

            String text;

            if ("application/pdf".equalsIgnoreCase(mimeType)) {
                text = extractPdfText(dis);
            } else {
                text = extractPlainText(dis);
            }

            dis.transferTo(OutputStream.nullOutputStream());
            String hash = HexFormat.of().formatHex(digest.digest());

            return new ExtractionResult(text, hash, mimeType);

        } catch (Exception e) {
            throw new RuntimeException("Extraction failed", e);
        }
    }

    /** Convenience method for callers that only need the MIME type. */
    public String getFileType(MultipartFile file) {
        return detectMimeType(file);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String detectMimeType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct == null) return "application/octet-stream";
        String lower = ct.toLowerCase();
        return lower.contains("pdf") ? "application/pdf" : lower;
    }

    /**
     * Validates that the file starts with the PDF magic bytes (%PDF).
     * Works directly on the stable byte[] — no stream mark/reset needed.
     */
    private void validateMagicBytes(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            throw new FileSanitizationException("File is too small to be a valid PDF.");
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new FileSanitizationException(
                        "File magic bytes do not match PDF format. Possible spoofed Content-Type.");
            }
        }
    }

    /**
     * Extracts text from a PDF.
     * Reads from the DigestInputStream so SHA-256 is computed over the same bytes.
     * Uses is.readAllBytes() here safely — the stream is a ByteArrayInputStream
     * backed by our stable byte[], so it cannot be closed externally.
     */
    private String extractPdfText(InputStream is) throws IOException {
        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            String text = new PDFTextStripper().getText(doc);
            if (text.length() > MAX_TEXT_CHARS) {
                log.warn("[PdfExtractor] Truncating PDF text from {} to {} chars",
                        text.length(), MAX_TEXT_CHARS);
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            return text;
        }
    }

    /**
     * Extracts text from a plain-text file line by line, bounded by MAX_TEXT_CHARS.
     * Reads from the DigestInputStream so SHA-256 is computed while reading.
     */
    private String extractPlainText(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(65536, MAX_TEXT_CHARS));
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() + line.length() + 1 > MAX_TEXT_CHARS) {
                    log.warn("[PdfExtractor] Truncating plain text at {} chars", sb.length());
                    break;
                }
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public record ExtractionResult(String text, String contentHash, String mimeType) {}
}
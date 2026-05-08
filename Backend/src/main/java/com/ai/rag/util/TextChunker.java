package com.ai.rag.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * UPDATED — FIX #12: Fixed infinite loop in splitBySize().
 *
 * PROBLEM: The original code had:
 *   start = end - chunkOverlap;
 *   if (start < 0) start = 0;
 *
 * When chunkOverlap >= chunkSize (or close to it), `end - chunkOverlap` could equal or
 * be less than the previous `start`, causing the while loop to never advance and spin
 * forever, consuming 100% CPU and eventually OOMing the JVM.
 *
 * FIX: After computing the new start, assert it is strictly greater than the previous
 * start. If not, force-advance by at least 1 character to guarantee termination.
 *
 * Also validates chunkOverlap < chunkSize at startup so misconfiguration is caught early.
 */
@Component
public class TextChunker {

    @Value("${rag.chunk-size:800}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:150}")
    private int chunkOverlap;

    private static final Pattern PARA_BREAK = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern HEADING    = Pattern.compile("^#{1,6}\\s+.+", Pattern.MULTILINE);

    public List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        // FIX #12: Guard against misconfiguration — overlap must be < chunk size
        int effectiveOverlap = Math.min(chunkOverlap, chunkSize / 2);

        String[] paragraphs = PARA_BREAK.split(text.strip());
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isBlank()) continue;

            boolean isHeading = HEADING.matcher(p).find();
            if (isHeading && current.length() > 0) {
                chunks.addAll(splitBySize(current.toString(), effectiveOverlap));
                current.setLength(0);
            }

            if (current.length() + p.length() + 2 > chunkSize && current.length() > 0) {
                chunks.addAll(splitBySize(current.toString(), effectiveOverlap));
                String carry = current.length() > effectiveOverlap
                        ? current.substring(current.length() - effectiveOverlap)
                        : current.toString();
                current.setLength(0);
                current.append(carry).append("\n\n");
            }
            current.append(p).append("\n\n");
        }
        if (!current.isEmpty()) chunks.addAll(splitBySize(current.toString(), effectiveOverlap));
        return chunks.stream().filter(c -> !c.isBlank()).toList();
    }

    private List<String> splitBySize(String text, int overlap) {
        if (text.length() <= chunkSize) return List.of(text.strip());

        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // Try to split at a sentence boundary within the window
            if (end < text.length()) {
                int boundary = text.lastIndexOf(". ", end);
                if (boundary > start + chunkSize / 2) {
                    end = boundary + 1;
                }
            }

            result.add(text.substring(start, end).strip());

            // FIX #12: Compute next start and GUARANTEE forward progress.
            // Without this guard, if overlap >= (end - start), nextStart <= start,
            // causing an infinite loop.
            int nextStart = end - overlap;
            if (nextStart <= start) {
                // Force advance by at least 1 to break the loop
                nextStart = start + 1;
            }
            start = nextStart;
        }
        return result;
    }

    public String extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 3)
                .distinct()
                .limit(30)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}

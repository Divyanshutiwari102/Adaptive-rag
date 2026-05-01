package com.ai.rag.util;

import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * UPDATED — FIX #11: Prevent cache key privacy leak.
 *
 * PROBLEM: The previous key was the raw text (or a substring of it):
 *   key = "#text.substring(0, T(Math).min(#text.length(), 250))"
 *
 * This embeds user query text directly in the Redis key. Redis key-space
 * inspection (KEYS *, SCAN) or key enumeration by a privileged Redis user
 * would expose PII — user questions, document snippets, etc.
 *
 * Additionally, substring(0, 250) creates hash collisions: two different
 * texts that share the same first 250 chars get the same cache key and
 * return wrong embeddings — a silent correctness bug.
 *
 * FIX:
 * 1. SHA-256 hash of the full text — unique, no collisions, no PII in keys.
 * 2. "emb:" prefix keeps the key-space organised and avoids collisions with
 *    other cache namespaces sharing the same Redis instance.
 * 3. Full text (up to 8000 chars) is hashed — not a prefix — so the key
 *    correctly distinguishes any two distinct inputs.
 */
@Component("embeddingCacheKeyUtil")
public class EmbeddingCacheKeyUtil {

    private static final String PREFIX = "emb:";

    /**
     * Returns a deterministic, collision-free, opaque Redis cache key for the given text.
     * The raw text is never stored in or derivable from the key.
     */
    public String key(String text) {
        if (text == null || text.isBlank()) return PREFIX + "empty";
        return PREFIX + Hashing.sha256()
                .hashString(text, StandardCharsets.UTF_8)
                .toString();
    }
}

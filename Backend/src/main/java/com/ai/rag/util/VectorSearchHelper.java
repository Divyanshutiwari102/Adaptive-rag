package com.ai.rag.util;

import org.springframework.stereotype.Component;

/**
 * Utility to convert float[] embeddings to pgvector's wire format.
 *
 * pgvector expects vectors as the string "[0.1,0.2,-0.3,...]"
 * when passed as a native query parameter.
 *
 * The old CosineSimilarity class stored comma-CSV without brackets —
 * this class handles the correct format for native SQL.
 */
@Component
public class VectorSearchHelper {

    /**
     * Convert a float[] to pgvector string format: "[f0,f1,...,fn]"
     * Example: [0.1f, -0.3f, 0.8f] → "[0.1,-0.3,0.8]"
     */
    public static String toVectorString(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Embedding must not be null or empty");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}

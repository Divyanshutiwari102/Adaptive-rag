package com.ai.rag.util;

import com.ai.rag.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MmrReranker {

    private static final double LAMBDA = 0.7;

    public List<DocumentChunk> rerank(List<DocumentChunk> candidates, float[] queryVec, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= topK) return candidates;

        List<DocumentChunk> selected = new ArrayList<>();
        Set<UUID> selectedIds = new HashSet<>();

        for (int i = 0; i < topK; i++) {
            double bestScore = Double.NEGATIVE_INFINITY;
            DocumentChunk bestChunk = null;

            for (DocumentChunk candidate : candidates) {
                if (selectedIds.contains(candidate.getId())) continue;

                float[] candidateVec = toFloatArray(candidate.getEmbeddingVec());
                if (candidateVec == null) continue;

                double relevance = cosine(candidateVec, queryVec);
                double diversity = maxSimilarityToSelected(candidateVec, selected);

                double mmrScore = LAMBDA * relevance - (1 - LAMBDA) * diversity;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestChunk = candidate;
                }
            }

            if (bestChunk == null) break;

            selected.add(bestChunk);
            selectedIds.add(bestChunk.getId());
        }

        return selected;
    }

    private double maxSimilarityToSelected(float[] candidateVec, List<DocumentChunk> selected) {
        double max = 0.0;

        for (DocumentChunk s : selected) {
            float[] vec = toFloatArray(s.getEmbeddingVec());
            if (vec == null) continue;

            double sim = cosine(candidateVec, vec);
            if (sim > max) max = sim;
        }

        return max;
    }

    // 🔥 FIX: Safe conversion
    private float[] toFloatArray(Object obj) {
        if (obj == null) return null;

        if (obj instanceof float[]) {
            return (float[]) obj;
        }

        if (obj instanceof List<?>) {
            List<?> list = (List<?>) obj;
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object val = list.get(i);
                if (val instanceof Number) {
                    arr[i] = ((Number) val).floatValue();
                } else {
                    return null;
                }
            }
            return arr;
        }

        return null;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;

        double dot = 0, normA = 0, normB = 0;

        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        return (normA == 0 || normB == 0)
                ? 0.0
                : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
package com.ai.rag.service;

import com.ai.rag.util.VectorSearchHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class VectorSearchHelperTest {

    @Test
    @DisplayName("float[] converts to pgvector string format [f0,f1,...]")
    void toVectorString_correctFormat() {
        float[] vec = {0.1f, -0.3f, 0.8f};
        String result = VectorSearchHelper.toVectorString(vec);

        assertThat(result).startsWith("[");
        assertThat(result).endsWith("]");
        assertThat(result).contains("0.1");
        assertThat(result).contains("-0.3");
        assertThat(result).contains("0.8");
    }

    @Test
    @DisplayName("Single-element vector produces [value]")
    void singleElement_correctFormat() {
        assertThat(VectorSearchHelper.toVectorString(new float[]{1.0f})).isEqualTo("[1.0]");
    }

    @Test
    @DisplayName("1536-dim vector produces exactly 1536 comma-separated values")
    void largeDimension_correctCount() {
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) vec[i] = i * 0.001f;

        String result = VectorSearchHelper.toVectorString(vec);

        // Count commas = count of values - 1
        long commas = result.chars().filter(c -> c == ',').count();
        assertThat(commas).isEqualTo(1535);
    }

    @Test
    @DisplayName("Null embedding throws IllegalArgumentException")
    void nullEmbedding_throws() {
        assertThatThrownBy(() -> VectorSearchHelper.toVectorString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Empty embedding throws IllegalArgumentException")
    void emptyEmbedding_throws() {
        assertThatThrownBy(() -> VectorSearchHelper.toVectorString(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

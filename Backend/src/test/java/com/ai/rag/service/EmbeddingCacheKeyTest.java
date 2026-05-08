package com.ai.rag.service;

import com.ai.rag.util.EmbeddingCacheKeyUtil;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the SHA-256 cache key fix.
 * In v1, substring(0,250) caused collisions for texts sharing first 250 chars.
 */
class EmbeddingCacheKeyTest {

    EmbeddingCacheKeyUtil keyUtil = new EmbeddingCacheKeyUtil();

    @Test @DisplayName("Same text produces same key (deterministic)")
    void sameText_sameKey() {
        String key1 = keyUtil.key("hello world");
        String key2 = keyUtil.key("hello world");
        assertThat(key1).isEqualTo(key2);
    }

    @Test @DisplayName("Different texts produce different keys (no collision)")
    void differentText_differentKey() {
        String common = "x".repeat(300);
        String text1 = common + "suffix_A";
        String text2 = common + "suffix_B";
        // In v1 these would both produce key = substring(text,0,250) == same key
        assertThat(keyUtil.key(text1)).isNotEqualTo(keyUtil.key(text2));
    }

    @Test @DisplayName("Key is a valid 64-char hex string (SHA-256)")
    void key_isSha256Hex() {
        String key = keyUtil.key("any text");
        assertThat(key).hasSize(64).matches("[0-9a-f]+");
    }
}

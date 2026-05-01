package com.ai.rag.service;

import com.ai.rag.config.RateLimitConfig;
import com.ai.rag.resilience.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        config.setAsk(new RateLimitConfig.LimitSpec(5, 5, 60));     // 5 requests/min
        config.setIngest(new RateLimitConfig.LimitSpec(2, 2, 60));  // 2 uploads/min
        rateLimiter = new RateLimiterService(config);
    }

    @Test
    @DisplayName("Requests within limit are allowed")
    void withinLimit_allowed() {
        String userId = "user-1";
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume("ask", userId)).isTrue();
        }
    }

    @Test
    @DisplayName("Request exceeding limit is rejected")
    void exceedingLimit_rejected() {
        String userId = "user-2";
        for (int i = 0; i < 5; i++) rateLimiter.tryConsume("ask", userId);   // exhaust

        assertThat(rateLimiter.tryConsume("ask", userId)).isFalse();
    }

    @Test
    @DisplayName("Different users have independent buckets")
    void differentUsers_independentBuckets() {
        // Exhaust user-A
        for (int i = 0; i < 5; i++) rateLimiter.tryConsume("ask", "user-A");
        assertThat(rateLimiter.tryConsume("ask", "user-A")).isFalse();

        // user-B is unaffected
        assertThat(rateLimiter.tryConsume("ask", "user-B")).isTrue();
    }

    @Test
    @DisplayName("ask and ingest buckets are independent per user")
    void askAndIngest_independentBuckets() {
        String userId = "user-3";
        // Exhaust ingest (limit=2)
        rateLimiter.tryConsume("ingest", userId);
        rateLimiter.tryConsume("ingest", userId);
        assertThat(rateLimiter.tryConsume("ingest", userId)).isFalse();

        // ask is still available (limit=5)
        assertThat(rateLimiter.tryConsume("ask", userId)).isTrue();
    }
}

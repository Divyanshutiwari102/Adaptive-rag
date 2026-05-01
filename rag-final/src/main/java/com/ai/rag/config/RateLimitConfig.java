package com.ai.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rate-limit")
@Getter
@Setter
public class RateLimitConfig {

    private LimitSpec ask    = new LimitSpec(20, 20, 60);
    private LimitSpec ingest = new LimitSpec(5, 5, 60);

    @Getter
    @Setter
    public static class LimitSpec {
        private long capacity;
        private long refillTokens;
        private long refillSeconds;

        public LimitSpec() {}

        public LimitSpec(long capacity, long refillTokens, long refillSeconds) {
            this.capacity      = capacity;
            this.refillTokens  = refillTokens;
            this.refillSeconds = refillSeconds;
        }
    }
}

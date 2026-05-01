package com.ai.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "openai")
@Getter
@Setter
public class OpenAiProperties {
    private String apiKey;
    private String baseUrl;
    /** Full model for final answer generation (gpt-4o). */
    private String chatModel = "gpt-4o";
    /**
     * FIX #6: Cheaper mini model for grade/rewrite/HyDE tasks.
     * ~20x cheaper than gpt-4o with equivalent quality for these short-output tasks.
     */
    private String miniModel = "gpt-4o-mini";
    private String embeddingModel = "text-embedding-3-small";
    private int embeddingDimensions = 1536;
    private int maxTokens = 1500;
    private double temperature = 0.3;
    private int timeoutSeconds = 60;
}

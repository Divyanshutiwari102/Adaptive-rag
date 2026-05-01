package com.ai.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagProperties {
    private int    chunkSize             = 800;
    private int    chunkOverlap          = 150;
    private int    maxRetrievalResults   = 6;
    private double similarityThreshold   = 0.70;
    private int    maxContextTokens      = 4000;
    private int    efSearch              = 100;
    private boolean hydeEnabled          = true;
    private boolean crossEncoderEnabled  = false;
    private int    parallelEmbedPoolSize = 5;
    private int    simpleQueryMaxWords   = 5;

    /** FIX #5: per-user daily spend cap in USD. Set 0 to disable. */
    private boolean enforceSpendCap   = true;
    private double  dailySpendCapUsd  = 5.0;
}

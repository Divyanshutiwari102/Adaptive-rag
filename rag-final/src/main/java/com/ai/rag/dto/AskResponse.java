package com.ai.rag.dto;
import com.ai.rag.enums.QueryRoute;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;
@Data @Builder
public class AskResponse {
    private UUID queryId;
    private String sessionId;
    private String query;
    private String answer;
    private QueryRoute routeTaken;
    private boolean queryRewritten;
    private String rewrittenQuery;
    private long latencyMs;
    private Integer totalTokens;
    private BigDecimal estimatedCostUsd;
}

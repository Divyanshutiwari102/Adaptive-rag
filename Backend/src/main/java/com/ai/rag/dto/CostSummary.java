package com.ai.rag.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class CostSummary {
    private String     periodMonth;
    private long       totalTokens;
    private BigDecimal totalCostUsd;
    /** FIX #5: Today's spend vs daily cap. */
    private BigDecimal todayCostUsd;
}

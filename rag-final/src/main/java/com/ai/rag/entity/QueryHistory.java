package com.ai.rag.entity;

import com.ai.rag.enums.QueryRoute;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "query_history", indexes = {
        @Index(name = "idx_qh_user_id",      columnList = "user_id"),
        @Index(name = "idx_qh_session_id",   columnList = "session_id"),
        @Index(name = "idx_qh_created_at",   columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QueryHistory {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalQuery;

    @Column(columnDefinition = "TEXT")
    private String rewrittenQuery;

    @Column(columnDefinition = "TEXT")
    private String retrievedContext;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String finalAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueryRoute routeTaken;

    private Boolean retrievalGradePass;

    @Column(nullable = false) @Builder.Default
    private boolean queryRewritten = false;

    private Long latencyMs;

    // Cost tracking
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    @Column(precision = 10, scale = 6)
    private BigDecimal estimatedCostUsd;

    @CreationTimestamp @Column(updatable = false)
    private LocalDateTime createdAt;
}

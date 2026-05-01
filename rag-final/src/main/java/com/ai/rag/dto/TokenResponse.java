package com.ai.rag.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
@Data @Builder @AllArgsConstructor
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private long accessExpiresInMs;
    private String tokenType;
}

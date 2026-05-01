package com.ai.rag.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class AskRequest {
    @NotBlank(message = "Query must not be blank")
    @Size(max = 2000, message = "Query must be 2000 characters or fewer")
    private String query;
    @Size(max = 128)
    private String sessionId;
}

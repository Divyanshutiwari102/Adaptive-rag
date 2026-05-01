package com.ai.rag.service;
import com.ai.rag.dto.AskRequest;
import com.ai.rag.dto.AskResponse;
import com.ai.rag.entity.QueryHistory;
import com.ai.rag.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
public interface RagPipelineService {
    AskResponse process(AskRequest request, User user);
    Page<QueryHistory> getHistory(User user, String sessionId, Pageable pageable);
    QueryHistory getHistoryById(UUID id, User user);
}

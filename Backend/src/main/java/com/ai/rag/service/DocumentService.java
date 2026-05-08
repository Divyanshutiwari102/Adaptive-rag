package com.ai.rag.service;

import com.ai.rag.entity.Document;
import com.ai.rag.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DocumentService {

    Document acceptUpload(byte[] fileBytes,
                          String originalFilename,
                          String contentType,
                          String description,
                          User uploader);

    void runIngestionAsync(UUID documentId);

    Page<Document> getDocumentsForUser(User user, Pageable pageable);

    Document getDocumentById(UUID id, User user);

    void deleteDocument(UUID id, User user);
}
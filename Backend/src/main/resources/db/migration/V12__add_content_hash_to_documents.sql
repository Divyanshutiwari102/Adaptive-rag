-- V12__add_content_hash_to_documents.sql
-- Adds content_hash column to documents table for duplicate detection per user.
ALTER TABLE documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_documents_content_hash ON documents (content_hash, uploaded_by);
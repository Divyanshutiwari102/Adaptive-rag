-- V13__add_storage_key_to_documents.sql
--
-- Adds storage_key column to the documents table for S3 integration.
--
-- CONTEXT:
--   Before this migration, uploaded files had their extracted text stored in
--   the `raw_text` column. After S3 integration, the original file is stored
--   in AWS S3 and only the S3 object key is persisted here.
--
-- BACKWARD COMPATIBILITY:
--   Column is nullable so existing rows (pre-S3) are unaffected.
--   IngestionServiceImpl falls back to `raw_text` when `storage_key` is null.
--
-- COLUMN: VARCHAR(512) is generous — typical S3 keys are under 200 chars.
--   Format: "documents/{userId}/{uuid}/{filename}"
--   Example: "documents/a1b2c3/550e8400-e29b-41d4-a716/report.pdf"

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS storage_key VARCHAR(512);

-- Index for efficient lookup during cleanup / admin queries.
-- Not needed for the main ingestion path (which looks up by document id).
CREATE INDEX IF NOT EXISTS idx_documents_storage_key
    ON documents (storage_key)
    WHERE storage_key IS NOT NULL;

-- Optional comment for DBAs
COMMENT ON COLUMN documents.storage_key IS
    'S3 object key for the original uploaded file. Null for pre-S3 documents.';
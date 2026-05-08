-- V4__async_ingestion_fields.sql
--
-- Consolidates the duplicate V3 migration content.
-- The original project had two V3 files which caused Flyway to refuse startup.
-- This file (V4) contains the async ingestion additions that were previously
-- in the conflicting V3__async_ingestion_and_fts_index.sql.
--
-- Note: The GIN FTS index on content_tsv is handled by the GENERATED ALWAYS
-- column + GIN index in V2__add_pgvector.sql (already correct).
-- This migration only adds enhanced_description if not already present.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS enhanced_description TEXT;

-- Composite index for monthly cost rollup queries (GET /api/rag/cost/current-month)
CREATE INDEX IF NOT EXISTS idx_qh_user_created
    ON query_history (user_id, created_at DESC);

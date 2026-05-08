-- V8__rls_restrictive_and_fts_gin_index.sql

-- ── Drop old PERMISSIVE policies ──────────────────────────────────────────
DROP POLICY IF EXISTS tenant_isolation ON document_chunks;
DROP POLICY IF EXISTS tenant_isolation ON documents;

-- ── Add tsvector generated column ─────────────────────────────────────────
ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

-- FIX: Removed CONCURRENTLY (Flyway compatible)
CREATE INDEX IF NOT EXISTS idx_chunks_content_gin
ON document_chunks USING GIN (content_tsv);

-- ── Create RESTRICTIVE RLS policies ───────────────────────────────────────
CREATE POLICY tenant_isolation ON document_chunks
    AS RESTRICTIVE
    FOR ALL
    USING (
        current_setting('app.current_user_id', TRUE) IS NOT NULL
        AND current_setting('app.current_user_id', TRUE) <> ''
        AND document_id IN (
            SELECT id
            FROM documents
            WHERE uploaded_by = current_setting('app.current_user_id', TRUE)::uuid
        )
    );

CREATE POLICY tenant_isolation ON documents
    AS RESTRICTIVE
    FOR ALL
    USING (
        current_setting('app.current_user_id', TRUE) IS NOT NULL
        AND current_setting('app.current_user_id', TRUE) <> ''
        AND uploaded_by = current_setting('app.current_user_id', TRUE)::uuid
    );

-- ── Ensure RLS is enforced ────────────────────────────────────────────────
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks FORCE ROW LEVEL SECURITY;

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
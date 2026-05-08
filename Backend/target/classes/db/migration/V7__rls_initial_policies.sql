-- V7__rls_initial_policies.sql
-- Creates initial RLS policies (PERMISSIVE) and the rag_migrator bypass role.
-- These policies are replaced with RESTRICTIVE versions in V8.
-- V5 enabled RLS on both tables; this migration creates the actual allow policies.

-- ── Create rag_migrator role with BYPASSRLS (for Flyway only) ────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rag_migrator') THEN
        CREATE ROLE rag_migrator WITH BYPASSRLS NOLOGIN;
    END IF;
END;
$$;

-- ── Initial tenant isolation policies (PERMISSIVE) ────────────────────────────
-- These are upgraded to RESTRICTIVE in V8.
CREATE POLICY tenant_isolation ON document_chunks
    AS PERMISSIVE FOR ALL
    USING (
        current_setting('app.current_user_id', TRUE) IS NOT NULL
        AND current_setting('app.current_user_id', TRUE) <> ''
        AND document_id IN (
            SELECT id FROM documents
            WHERE uploaded_by = current_setting('app.current_user_id', TRUE)::uuid
        )
    );

CREATE POLICY tenant_isolation ON documents
    AS PERMISSIVE FOR ALL
    USING (
        current_setting('app.current_user_id', TRUE) IS NOT NULL
        AND current_setting('app.current_user_id', TRUE) <> ''
        AND uploaded_by = current_setting('app.current_user_id', TRUE)::uuid
    );

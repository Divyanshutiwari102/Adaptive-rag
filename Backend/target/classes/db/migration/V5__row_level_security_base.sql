-- V5__row_level_security_base.sql
--
-- Enables RLS on document_chunks and documents.
-- The actual restrictive policy is created in V7__rls_harden_no_null_bypass.sql.
-- This migration only enables the RLS mechanism and declares the GUC namespace.

-- Enable RLS (no policy yet — all rows visible to connections with BYPASSRLS)
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks FORCE ROW LEVEL SECURITY;
ALTER TABLE documents       ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents       FORCE ROW LEVEL SECURITY;

-- Pre-declare the GUC namespace so non-superuser roles can SET LOCAL it.
-- This is equivalent to adding app.current_user_id='' to postgresql.conf,
-- but scoped to connections using this role.
-- Run this as superuser once after DB creation:
--   ALTER ROLE rag_app_user SET app.current_user_id = '';
-- The Flyway migration documents this requirement but cannot execute it
-- (ALTER ROLE on yourself is not permitted in a migration).

-- V14__widen_file_type_column.sql
--
-- BUG FIX: file_type VARCHAR(10) is too narrow for MIME types like "application/pdf" (15 chars).
-- This caused DataIntegrityViolationException on every document upload.
--
-- Root cause in logs:
--   ERROR: value too long for type character varying(10)
--   INSERT INTO documents ... values ... ('application/pdf') ...
--
-- Fix: Widen to VARCHAR(100) which comfortably fits all MIME types.
-- ALTER COLUMN TYPE is safe in PostgreSQL — it rewrites only if needed, no data loss.

ALTER TABLE documents
ALTER COLUMN file_type TYPE VARCHAR(100);
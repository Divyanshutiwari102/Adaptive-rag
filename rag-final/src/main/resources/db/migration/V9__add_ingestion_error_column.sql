-- V9__add_ingestion_error_column.sql

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS ingestion_error TEXT;
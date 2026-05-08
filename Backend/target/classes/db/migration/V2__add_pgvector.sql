-- V2__add_pgvector.sql
-- Requires: PostgreSQL >= 14 + pgvector extension (pgvector >= 0.5.0 for HNSW)
-- Production install: CREATE EXTENSION vector;  (needs superuser once per DB)

CREATE EXTENSION IF NOT EXISTS vector;

-- Add native vector(1536) column alongside the legacy TEXT column.
-- We keep embedding_json for zero-downtime migration: old code still writes TEXT,
-- new code writes to embedding_vec. Once all chunks are backfilled, drop embedding_json.
ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS embedding_vec vector(1536);

-- HNSW index — sub-linear ANN search, no training required, better recall than IVFFlat.
-- m=16 ef_construction=64 are sane defaults for 1536-dim OpenAI embeddings.
-- For datasets > 1M vectors, tune m=32 ef_construction=128.
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON document_chunks
    USING hnsw (embedding_vec vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Optionally set ef_search at session level for query-time accuracy/speed trade-off:
-- SET hnsw.ef_search = 100;

-- V1__initial_schema.sql
-- Base schema: users, documents, document_chunks, query_history
-- Replaces ddl-auto: update. Run once on a clean database.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- for gen_random_uuid()

-- ── users ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(100) NOT NULL UNIQUE,
    password    TEXT         NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);

-- ── documents ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS documents (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    filename             TEXT         NOT NULL,
    original_description TEXT,
    enhanced_description TEXT,
    file_type            VARCHAR(10)  NOT NULL,
    total_chunks         INT          NOT NULL DEFAULT 0,
    ingestion_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING/PROCESSING/DONE/FAILED
    uploaded_by          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    uploaded_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_uploaded_by ON documents (uploaded_by);

-- ── document_chunks ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS document_chunks (
    id              UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID  NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index     INT   NOT NULL,
    content         TEXT  NOT NULL,
    keywords        TEXT,
    embedding_json  TEXT  -- legacy CSV column; kept for migration safety
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id  ON document_chunks (document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_chunk_index  ON document_chunks (chunk_index);

-- ── query_history ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS query_history (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id          TEXT        NOT NULL,
    original_query      TEXT        NOT NULL,
    rewritten_query     TEXT,
    retrieved_context   TEXT,
    final_answer        TEXT        NOT NULL,
    route_taken         VARCHAR(20) NOT NULL,
    retrieval_grade_pass BOOLEAN,
    query_rewritten     BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms          BIGINT,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_qh_user_id    ON query_history (user_id);
CREATE INDEX IF NOT EXISTS idx_qh_session_id ON query_history (session_id);
CREATE INDEX IF NOT EXISTS idx_qh_created_at ON query_history (created_at DESC);

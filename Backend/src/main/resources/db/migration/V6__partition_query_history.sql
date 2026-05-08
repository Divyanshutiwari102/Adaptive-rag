-- V6__partition_query_history.sql

-- Step 1: Create partitioned version
CREATE TABLE IF NOT EXISTS query_history_partitioned (
                                                         id                   UUID        NOT NULL,
                                                         user_id              UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id           TEXT        NOT NULL,
    original_query       TEXT        NOT NULL,
    rewritten_query      TEXT,
    retrieved_context    TEXT,
    final_answer         TEXT        NOT NULL,
    route_taken          VARCHAR(20) NOT NULL,
    retrieval_grade_pass BOOLEAN,
    query_rewritten      BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms           BIGINT,
    prompt_tokens        INT,
    completion_tokens    INT,
    total_tokens         INT,
    estimated_cost_usd   NUMERIC(10,6),
    created_at           TIMESTAMP   NOT NULL DEFAULT NOW()
    ) PARTITION BY RANGE (created_at);

-- Step 2: Create partitions for current month + 3 ahead
DO $$
DECLARE
m DATE;
    pname TEXT;
BEGIN
FOR i IN 0..3 LOOP
        m := DATE_TRUNC('month', NOW()) + (i || ' months')::INTERVAL;
        pname := 'query_history_' || TO_CHAR(m, 'YYYY_MM');
EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF query_history_partitioned
         FOR VALUES FROM (%L) TO (%L)',
        pname, m, m + INTERVAL '1 month'
        );
END LOOP;
END;
$$;

-- Step 3: Indexes
CREATE INDEX IF NOT EXISTS idx_qhp_user_session
    ON query_history_partitioned (user_id, session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_qhp_created_at
    ON query_history_partitioned (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_qhp_user_date
    ON query_history_partitioned (user_id, created_at DESC);

-- Step 4: SAFE DATA COPY (FIXED ❗)
-- Step 4: SAFE DATA COPY (FINAL FIX ✅)
INSERT INTO query_history_partitioned (
    id,
    user_id,
    session_id,
    original_query,
    rewritten_query,
    retrieved_context,
    final_answer,
    route_taken,
    retrieval_grade_pass,
    query_rewritten,
    latency_ms,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    estimated_cost_usd,
    created_at
)
SELECT
    id,
    user_id,
    session_id,
    original_query,
    rewritten_query,
    retrieved_context,
    final_answer,
    route_taken,
    retrieval_grade_pass,
    query_rewritten,
    latency_ms,
    NULL AS prompt_tokens,
    NULL AS completion_tokens,
    NULL AS total_tokens,
    NULL AS estimated_cost_usd,
    created_at
FROM query_history;

-- Step 5: Atomic swap
ALTER TABLE query_history              RENAME TO query_history_legacy;
ALTER TABLE query_history_partitioned  RENAME TO query_history;

COMMENT ON TABLE query_history_legacy IS
    'Backup before partitioning. Safe to drop after verification.';
-- V3__indexes_and_session.sql
CREATE INDEX IF NOT EXISTS idx_qh_user_session
    ON query_history (user_id, session_id, created_at DESC);

-- Composite index for cost aggregation queries
CREATE INDEX IF NOT EXISTS idx_qh_user_month
    ON query_history (user_id, date_trunc('month', created_at));

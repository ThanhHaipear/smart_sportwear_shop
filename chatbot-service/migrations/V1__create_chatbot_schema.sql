CREATE SCHEMA IF NOT EXISTS chatbot;

CREATE TABLE IF NOT EXISTS chatbot.chat_sessions (
    id varchar(128) PRIMARY KEY,
    user_id varchar(128),
    channel varchar(32) NOT NULL DEFAULT 'web',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chatbot.chat_messages (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id varchar(128) NOT NULL REFERENCES chatbot.chat_sessions(id) ON DELETE CASCADE,
    user_id varchar(128),
    role varchar(16) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content text NOT NULL,
    intent varchar(64),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_chat_messages_session_created
    ON chatbot.chat_messages(session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS chatbot.retrieval_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id varchar(128),
    query text NOT NULL,
    candidate_product_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    search_mode varchar(32),
    latency_ms integer NOT NULL CHECK (latency_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_retrieval_logs_session_created
    ON chatbot.retrieval_logs(session_id, created_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbot_app') THEN
        REVOKE CREATE ON SCHEMA public FROM chatbot_app;
        GRANT USAGE ON SCHEMA chatbot TO chatbot_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA chatbot TO chatbot_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA chatbot TO chatbot_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA chatbot
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO chatbot_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA chatbot
            GRANT USAGE, SELECT ON SEQUENCES TO chatbot_app;
    END IF;
END $$;

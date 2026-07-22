CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT,
    source_id    VARCHAR(128),
    title        VARCHAR(512),
    content      TEXT NOT NULL,
    embedding    vector(1536) NOT NULL,
    token_count  INT NOT NULL DEFAULT 0,
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_document_id
    ON ai_knowledge_chunk (document_id);

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_embedding_hnsw
    ON ai_knowledge_chunk
    USING hnsw (embedding vector_cosine_ops);
-- D23: 新增 Ollama 本地 embedding 列（768 维，nomic-embed-text）
-- 与原有 embedding(1536) 列共存，支持双维度检索

-- 新增 768 维向量列
ALTER TABLE ai_knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding_ollama vector(768);

-- 为 ollama embedding 列创建 HNSW 索引
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_embedding_ollama_hnsw
    ON ai_knowledge_chunk
    USING hnsw (embedding_ollama vector_cosine_ops);

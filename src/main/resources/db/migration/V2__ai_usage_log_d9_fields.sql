-- D9: ai_usage_log 补充 mode / latency / status，供成本统计闭环
ALTER TABLE ai_usage_log
    ADD COLUMN mode VARCHAR(32) NOT NULL DEFAULT 'chat' COMMENT '调用模式：chat / agent / stream' AFTER model,
    ADD COLUMN latency_ms BIGINT NOT NULL DEFAULT 0 COMMENT '本次调用耗时（毫秒）' AFTER cost,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '调用状态：SUCCESS / FAILED / DEGRADED' AFTER latency_ms;

CREATE INDEX idx_usage_status ON ai_usage_log (status);
CREATE INDEX idx_usage_mode ON ai_usage_log (mode);
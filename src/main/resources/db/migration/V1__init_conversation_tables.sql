-- ============================================================
-- V1: 会话、消息、AI 用量日志
-- ============================================================

-- 会话表
CREATE TABLE conversation (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     VARCHAR(64)  NOT NULL                COMMENT '用户ID',
    title       VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (id),
    KEY idx_conversation_user_id (user_id),
    KEY idx_conversation_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- 消息表
CREATE TABLE message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT       NOT NULL                COMMENT '所属会话ID',
    role            VARCHAR(32)  NOT NULL                COMMENT '角色：user / assistant / system',
    content         TEXT         NOT NULL                COMMENT '消息内容',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (id),
    KEY idx_message_conversation_id (conversation_id),
    KEY idx_message_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- AI 调用用量日志
CREATE TABLE ai_usage_log (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id   BIGINT        NULL                    COMMENT '关联会话ID',
    message_id        BIGINT        NULL                    COMMENT '关联消息ID',
    provider          VARCHAR(64)   NOT NULL                COMMENT 'Provider 名称，如 spring-ai-alibaba',
    model             VARCHAR(128)  NULL                    COMMENT '模型名称，如 qwen-plus',
    prompt_tokens     INT           NOT NULL DEFAULT 0      COMMENT '输入 Token 数',
    completion_tokens INT           NOT NULL DEFAULT 0      COMMENT '输出 Token 数',
    total_tokens      INT           NOT NULL DEFAULT 0      COMMENT '总 Token 数',
    cost              DECIMAL(12,6) NOT NULL DEFAULT 0     COMMENT '本次调用费用（元）',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_usage_conversation_id (conversation_id),
    KEY idx_usage_provider (provider),
    KEY idx_usage_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用用量日志';
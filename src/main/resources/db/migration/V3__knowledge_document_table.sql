-- D18: 文档管理表（文档管道状态跟踪）
CREATE TABLE knowledge_document (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)   NOT NULL                COMMENT '上传用户',
    file_name       VARCHAR(255)  NOT NULL                COMMENT '原始文件名',
    file_size       BIGINT        NOT NULL DEFAULT 0      COMMENT '文件字节数',
    mime_type       VARCHAR(128)  NULL                    COMMENT 'MIME 类型',
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED',
    total_chunks    INT           NOT NULL DEFAULT 0      COMMENT '总分块数',
    indexed_chunks  INT           NOT NULL DEFAULT 0      COMMENT '已入库分块数',
    error_message   TEXT          NULL                    COMMENT '失败原因',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_doc_user (user_id),
    KEY idx_doc_status (status),
    KEY idx_doc_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档管理';
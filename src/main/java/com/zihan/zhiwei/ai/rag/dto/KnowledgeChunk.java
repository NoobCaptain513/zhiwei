package com.zihan.zhiwei.ai.rag.dto;

import java.time.LocalDateTime;

public record KnowledgeChunk(
        Long id,
        Long documentId,
        String sourceId,
        String title,
        String content,
        int tokenCount,
        LocalDateTime createTime
) {}
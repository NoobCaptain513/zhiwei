package com.zihan.zhiwei.ai.rag.agentic.model;

public record Citation(
        String evidenceId,
        Long chunkId,
        Long documentId,
        String sourceId,
        String title,
        double score
) {}

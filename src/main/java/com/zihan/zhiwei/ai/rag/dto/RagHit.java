package com.zihan.zhiwei.ai.rag.dto;

public record RagHit(
        KnowledgeChunk chunk,
        double vectorScore,
        double lexicalScore,
        double finalScore
) {}
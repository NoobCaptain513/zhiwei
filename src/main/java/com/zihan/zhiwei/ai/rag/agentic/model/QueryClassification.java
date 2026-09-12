package com.zihan.zhiwei.ai.rag.agentic.model;

public record QueryClassification(
        boolean needRag,
        QuestionType questionType,
        boolean needFreshness,
        boolean multiHop,
        double confidence,
        String reason
) {}

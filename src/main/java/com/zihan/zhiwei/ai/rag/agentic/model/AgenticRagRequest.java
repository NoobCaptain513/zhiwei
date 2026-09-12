package com.zihan.zhiwei.ai.rag.agentic.model;

public record AgenticRagRequest(
        String query,
        String historyContext,
        String preferredProvider,
        String model
) {}

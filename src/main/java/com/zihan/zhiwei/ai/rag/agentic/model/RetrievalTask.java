package com.zihan.zhiwei.ai.rag.agentic.model;

import java.util.Map;

public record RetrievalTask(
        String subQuestion,
        KnowledgeSource source,
        RetrievalStrategy strategy,
        Map<String, Object> filters
) {
    public RetrievalTask {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}

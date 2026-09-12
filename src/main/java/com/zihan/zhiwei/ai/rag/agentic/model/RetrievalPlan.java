package com.zihan.zhiwei.ai.rag.agentic.model;

import java.util.List;

public record RetrievalPlan(
        List<RetrievalTask> tasks,
        int topK,
        int candidateK,
        String rationale
) {
    public RetrievalPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        topK = Math.max(1, topK);
        candidateK = Math.max(topK, candidateK);
    }
}

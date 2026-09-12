package com.zihan.zhiwei.ai.rag.agentic.model;

import com.zihan.zhiwei.ai.rag.dto.RagHit;

import java.util.List;

public record RetrievalResult(List<RagHit> hits, long latencyMs) {
    public RetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), 0L);
    }
}

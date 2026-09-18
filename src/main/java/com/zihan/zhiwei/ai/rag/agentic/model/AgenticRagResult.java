package com.zihan.zhiwei.ai.rag.agentic.model;

import java.util.List;

public record AgenticRagResult(
        boolean ragRequired,
        String answer,
        List<Citation> citations,
        int retrievalRounds,
        boolean sufficient,
        boolean conflictDetected,
        String terminationReason,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean degraded,
        long latencyMs
) {
    public AgenticRagResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Compatibility result for callers that decide not to enter Agentic RAG. */
    public static AgenticRagResult notRequired() {
        return new AgenticRagResult(false, null, List.of(), 0, false, false,
                "RAG_NOT_REQUIRED", "system", "router", 0, 0, 0, false, 0L);
    }
}

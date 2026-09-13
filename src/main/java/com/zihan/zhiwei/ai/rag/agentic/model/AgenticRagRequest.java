package com.zihan.zhiwei.ai.rag.agentic.model;

import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;

public record AgenticRagRequest(
        String query,
        String historyContext,
        String preferredProvider,
        String model,
        AgentRunContext runContext
) {
    public AgenticRagRequest(String query, String historyContext, String preferredProvider, String model) {
        this(query, historyContext, preferredProvider, model, null);
    }

    public AgenticRagRequest withRunContext(AgentRunContext context) {
        return new AgenticRagRequest(query, historyContext, preferredProvider, model, context);
    }

    /** Runtime context is execution metadata and deliberately not part of request identity. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AgenticRagRequest that)) return false;
        return java.util.Objects.equals(query, that.query)
                && java.util.Objects.equals(historyContext, that.historyContext)
                && java.util.Objects.equals(preferredProvider, that.preferredProvider)
                && java.util.Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(query, historyContext, preferredProvider, model);
    }
}

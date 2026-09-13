package com.zihan.zhiwei.ai.rag.agentic.model;

import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;

public record AgenticRagRequest(
        String query,
        String historyContext,
        String preferredProvider,
        String model,
        AgentRunContext runContext,
        String userId,
        Long conversationId
) {
    public AgenticRagRequest(String query, String historyContext, String preferredProvider, String model) {
        this(query, historyContext, preferredProvider, model, null, null, null);
    }

    public AgenticRagRequest(String query, String historyContext, String preferredProvider, String model,
                             AgentRunContext runContext) {
        this(query, historyContext, preferredProvider, model, runContext, null, null);
    }

    public AgenticRagRequest(String query, String historyContext, String preferredProvider, String model,
                             String userId, Long conversationId) {
        this(query, historyContext, preferredProvider, model, null, userId, conversationId);
    }

    public AgenticRagRequest withRunContext(AgentRunContext context) {
        return new AgenticRagRequest(query, historyContext, preferredProvider, model, context, userId, conversationId);
    }

    /** Runtime context is execution metadata and deliberately not part of request identity. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AgenticRagRequest that)) return false;
        return java.util.Objects.equals(query, that.query)
                && java.util.Objects.equals(historyContext, that.historyContext)
                && java.util.Objects.equals(preferredProvider, that.preferredProvider)
                && java.util.Objects.equals(model, that.model)
                && java.util.Objects.equals(userId, that.userId)
                && java.util.Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(query, historyContext, preferredProvider, model, userId, conversationId);
    }
}

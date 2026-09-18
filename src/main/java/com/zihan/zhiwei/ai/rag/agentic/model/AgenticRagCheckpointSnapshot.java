package com.zihan.zhiwei.ai.rag.agentic.model;

import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;
import com.zihan.zhiwei.ai.rag.agentic.RagState;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Durable state required to continue Agentic RAG from a node boundary. */
public record AgenticRagCheckpointSnapshot(
        int schemaVersion,
        String nextNode,
        String query,
        String historyContext,
        String preferredProvider,
        String model,
        QueryClassification classification,
        RetrievalPlan plan,
        List<RetrievalResult> rounds,
        EvidenceGrade latestGrade,
        int rewriteCount,
        AgentRunContext.Snapshot runContext
) {
    private static final Set<String> NODES = Set.of(
            "classify", "plan", "retrieve", "grade", "rewrite", "generate", "abstain");

    public AgenticRagCheckpointSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported Agentic RAG checkpoint schema: " + schemaVersion);
        }
        if (!NODES.contains(nextNode)) {
            throw new IllegalArgumentException("invalid Agentic RAG resume node: " + nextNode);
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("checkpoint query is required");
        }
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
        rewriteCount = Math.max(0, rewriteCount);
        if (runContext == null) {
            throw new IllegalArgumentException("checkpoint run context is required");
        }
        validateState(nextNode, classification, plan, rounds, latestGrade);
    }

    public static AgenticRagCheckpointSnapshot capture(
            String nextNode, RagState state, AgentRunContext context) {
        AgenticRagRequest request = state.getRequest();
        return new AgenticRagCheckpointSnapshot(
                1, nextNode, request.query(), request.historyContext(), request.preferredProvider(),
                request.model(), state.getClassification(),
                state.getPlan(), state.getRounds(), state.getLatestGrade(), state.getRewriteCount(),
                context.snapshot());
    }

    public AgenticRagRequest restoreRequest(AgentRunContext context, String userId, Long conversationId) {
        if (userId == null || userId.isBlank() || conversationId == null || conversationId <= 0) {
            throw new IllegalArgumentException("checkpoint envelope owner and conversation are required");
        }
        return new AgenticRagRequest(query, historyContext, preferredProvider, model,
                context, userId, conversationId);
    }

    public RagState restoreState(AgenticRagRequest request) {
        return RagState.restore(request, classification, plan, latestGrade, rewriteCount, rounds);
    }

    public void validatePolicy(int maxRewriteAttempts) {
        int maximum = Math.max(0, maxRewriteAttempts);
        if (rewriteCount > maximum || ("rewrite".equals(nextNode) && rewriteCount >= maximum)) {
            throw new IllegalArgumentException("checkpoint rewrite count exceeds current server policy");
        }
    }

    private static void validateState(String node, QueryClassification classification,
                                      RetrievalPlan plan, List<RetrievalResult> rounds,
                                      EvidenceGrade grade) {
        if (classification != null && !classification.needRag() && !"classify".equals(node)) {
            throw new IllegalArgumentException(node + " cannot resume after a no-RAG classification");
        }
        if (Set.of("retrieve", "grade", "rewrite", "generate", "abstain").contains(node)) {
            if (plan == null || plan.tasks().isEmpty() || plan.tasks().size() > 8
                    || plan.topK() > 50 || plan.candidateK() > 200) {
                throw new IllegalArgumentException(node + " requires a bounded retrieval plan");
            }
        }
        if (Set.of("grade", "rewrite", "generate", "abstain").contains(node) && rounds.isEmpty()) {
            throw new IllegalArgumentException(node + " requires retrieved evidence");
        }
        if ("rewrite".equals(node)
                && (grade == null || grade.sufficient() || grade.nextAction() == NextAction.ABSTAIN)) {
            throw new IllegalArgumentException("rewrite requires an insufficient retryable grade");
        }
        if ("generate".equals(node)) {
            if (grade == null || !grade.sufficient() || grade.acceptedEvidenceIds().isEmpty()) {
                throw new IllegalArgumentException("generate requires sufficient accepted evidence");
            }
            Set<Long> retrievedIds = new HashSet<>();
            rounds.stream().flatMap(round -> round.hits().stream())
                    .filter(hit -> hit.chunk() != null && hit.chunk().id() != null)
                    .forEach(hit -> retrievedIds.add(hit.chunk().id()));
            if (!retrievedIds.containsAll(grade.acceptedEvidenceIds())) {
                throw new IllegalArgumentException("generate accepted evidence is not present in retrieval rounds");
            }
        }
        if ("abstain".equals(node) && grade == null) {
            throw new IllegalArgumentException("abstain requires an evidence grade");
        }
    }
}

package com.zihan.zhiwei.ai.rag.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.ai.agent.runtime.AgentReliabilityProperties;
import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;
import com.zihan.zhiwei.ai.agent.runtime.TokenBudgetExceededException;
import com.zihan.zhiwei.ai.memory.CheckpointService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagCheckpointSnapshot;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.mapper.ConversationMapper;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
public class AgenticRagOrchestrator {
    private static final Set<String> NON_RETRYABLE_FAILURES = Set.of(
            "TOKEN_BUDGET_EXHAUSTED", "NODE_CALL_LIMIT", "DEADLINE_EXCEEDED");

    private final QueryPlanner planner;
    private final Retriever retriever;
    private final EvidenceGrader evidenceGrader;
    private final FeedbackQueryRewriter queryRewriter;
    private final AnswerGenerator answerGenerator;
    private final int maxRewriteAttempts;
    private final AgentNodeObserver observer;
    private final AgentReliabilityProperties reliabilityProperties;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private CheckpointService checkpointService;
    @Autowired(required = false)
    private MemoryProperties memoryProperties;
    @Autowired(required = false)
    private ConversationMapper conversationMapper;

    @Autowired
    public AgenticRagOrchestrator(
            QueryPlanner planner,
            Retriever retriever,
            EvidenceGrader evidenceGrader,
            FeedbackQueryRewriter queryRewriter,
            AnswerGenerator answerGenerator,
            @Value("${zhiwei.ai.rag.agentic.max-rewrite-attempts:2}") int maxRewriteAttempts,
            AgentNodeObserver observer,
            AgentReliabilityProperties reliabilityProperties,
            ObjectMapper objectMapper) {
        this.planner = planner;
        this.retriever = retriever;
        this.evidenceGrader = evidenceGrader;
        this.queryRewriter = queryRewriter;
        this.answerGenerator = answerGenerator;
        this.maxRewriteAttempts = Math.max(0, maxRewriteAttempts);
        this.observer = observer;
        this.reliabilityProperties = reliabilityProperties;
        this.objectMapper = objectMapper;
    }

    public AgenticRagOrchestrator(
            QueryPlanner planner,
            Retriever retriever,
            EvidenceGrader evidenceGrader,
            FeedbackQueryRewriter queryRewriter,
            AnswerGenerator answerGenerator,
            int maxRewriteAttempts) {
        this(planner, retriever, evidenceGrader, queryRewriter, answerGenerator,
                maxRewriteAttempts,
                new AgentNodeObserver(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new AgentReliabilityProperties(), new ObjectMapper().findAndRegisterModules());
    }

    public AgenticRagResult execute(AgenticRagRequest request) {
        AgentRunContext context = request.runContext() == null
                ? new AgentRunContext(reliabilityProperties.newBudget(), observer)
                : request.runContext();
        AgenticRagRequest effectiveRequest = request.runContext() == null
                ? request.withRunContext(context) : request;
        RagState state = RagState.initial(effectiveRequest);
        String runId = UUID.randomUUID().toString();
        AgentCheckpoint checkpoint = saveCheckpoint(
                effectiveRequest, runId, 0, "plan", state, context, null);
        return continueRun(effectiveRequest, context, state, runId, 1, "plan", checkpoint);
    }

    public AgenticRagResult resume(String userId, long checkpointId, long expectedVersion,
                                   String actorId, String reason, String requestId) {
        if (checkpointService == null) {
            throw new IllegalStateException("checkpoint service is unavailable");
        }
        AgentCheckpoint source = checkpointService.get(userId, checkpointId)
                .orElseThrow(() -> new IllegalArgumentException("checkpoint not found"));
        if (source.checkpointType() != AgentCheckpoint.Type.AGENTIC_RAG) {
            throw new IllegalArgumentException("checkpoint is not an Agentic RAG checkpoint");
        }
        int sourceSequence = source.sequenceNo() == null ? 0 : source.sequenceNo();
        if (checkpointService.hasLaterInRun(userId, source.runId(), sourceSequence, source.id())) {
            throw new IllegalStateException("checkpoint is superseded by a later run boundary");
        }
        if (source.status() == AgentCheckpoint.Status.FAILED
                && NON_RETRYABLE_FAILURES.contains(source.errorCode())) {
            throw new IllegalStateException("checkpoint failure is non-retryable without a budget extension");
        }
        verifyConversationOwnership(source);
        AgenticRagCheckpointSnapshot snapshot = decodeSnapshot(source);
        snapshot.validatePolicy(maxRewriteAttempts);
        AgentRunContext context = AgentRunContext.restore(
                snapshot.runContext(), observer, reliabilityProperties.newBudget());
        AgenticRagRequest request = snapshot.restoreRequest(context, source.userId(), source.conversationId());
        RagState state = snapshot.restoreState(request);
        AgentCheckpoint claimed = checkpointService.resume(
                userId, checkpointId, expectedVersion, actorId, reason, requestId);
        int nextSequence = sourceSequence + 1;
        String nextNode = "classify".equals(snapshot.nextNode()) ? "plan" : snapshot.nextNode();
        return continueRun(request, context, state, source.runId(), nextSequence, nextNode, claimed);
    }

    private AgenticRagResult continueRun(
            AgenticRagRequest request,
            AgentRunContext context,
            RagState state,
            String runId,
            int sequence,
            String firstNode,
            AgentCheckpoint initialCheckpoint) {
        AgentCheckpoint checkpoint = initialCheckpoint;
        String nextNode = firstNode;
        try {
            while (true) {
                switch (nextNode) {
                    case "plan" -> {
                        state.setPlan(context.observe("plan", () -> planner.plan(state)));
                        nextNode = "retrieve";
                    }
                    case "retrieve" -> {
                        state.addRound(context.observe("retrieve", () -> retriever.retrieve(state)));
                        nextNode = "grade";
                    }
                    case "grade" -> {
                        state.setLatestGrade(context.observe("grade", () -> evidenceGrader.grade(state)));
                        if (state.getLatestGrade().sufficient()) {
                            nextNode = "generate";
                        } else if (state.getLatestGrade().nextAction()
                                == com.zihan.zhiwei.ai.rag.agentic.model.NextAction.ABSTAIN
                                || state.getRewriteCount() >= maxRewriteAttempts) {
                            nextNode = "abstain";
                        } else {
                            nextNode = "rewrite";
                        }
                    }
                    case "rewrite" -> {
                        state.setPlan(context.observe("rewrite", () -> queryRewriter.rewrite(state)));
                        state.incrementRewriteCount();
                        log.info("[AgenticRAG] retry={} action={} gaps={}",
                                state.getRewriteCount(), state.getLatestGrade().nextAction(),
                                state.getLatestGrade().gaps());
                        nextNode = "retrieve";
                    }
                    case "generate" -> {
                        AgenticRagResult result = context.observe(
                                "generate", () -> answerGenerator.generateAndVerify(state));
                        completeCheckpoint(request, checkpoint, state, context, "generate");
                        return result;
                    }
                    case "abstain" -> {
                        AgenticRagResult result = context.observe("abstain", () -> answerGenerator.abstain(state));
                        completeCheckpoint(request, checkpoint, state, context, "abstain");
                        return result;
                    }
                    default -> throw new IllegalStateException("unsupported Agentic RAG node: " + nextNode);
                }
                checkpoint = saveCheckpoint(request, runId, sequence++, nextNode, state, context, checkpoint);
            }
        } catch (TokenBudgetExceededException e) {
            log.warn("[AgenticRAG] terminated reason={} usedTokens={} rounds={}",
                    e.reason(), context.totalTokens(), state.getRounds().size());
            failCheckpoint(request, checkpoint, state, context, e.reason());
            String answer = "Agent 执行预算已耗尽，已停止继续调用。";
            if ("DEADLINE_EXCEEDED".equals(e.reason())) {
                answer = "Agent 执行超时，已停止继续调用。";
            }
            return new AgenticRagResult(true, answer, java.util.List.of(), state.getRounds().size(),
                    false, false, e.reason(), "system", "none",
                    context.promptTokens(), context.completionTokens(), context.totalTokens(),
                    true, 0L);
        } catch (RuntimeException e) {
            failCheckpoint(request, checkpoint, state, context, e.getClass().getSimpleName());
            throw e;
        }
    }

    private AgenticRagCheckpointSnapshot decodeSnapshot(AgentCheckpoint checkpoint) {
        try {
            if (checkpoint.state() == null || checkpoint.state().path("resumeParameters").isMissingNode()
                    || checkpoint.state().path("resumeParameters").isNull()) {
                throw new IllegalArgumentException("checkpoint does not contain resumable Agentic RAG state");
            }
            String currentNode = checkpoint.state().path("currentNode").asText(null);
            if (!checkpoint.nodeName().equals(currentNode)) {
                throw new IllegalArgumentException("checkpoint node metadata is inconsistent");
            }
            AgenticRagCheckpointSnapshot snapshot = objectMapper.treeToValue(
                    checkpoint.state().path("resumeParameters"), AgenticRagCheckpointSnapshot.class);
            if (!currentNode.equals(snapshot.nextNode())) {
                throw new IllegalArgumentException("checkpoint node metadata is inconsistent");
            }
            return snapshot;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Agentic RAG checkpoint state", e);
        }
    }

    private void verifyConversationOwnership(AgentCheckpoint checkpoint) {
        if (conversationMapper == null) {
            throw new IllegalStateException("conversation ownership verifier is unavailable");
        }
        var conversation = conversationMapper.selectById(checkpoint.conversationId());
        if (conversation == null || !checkpoint.userId().equals(conversation.getUserId())) {
            throw new AccessDeniedException("checkpoint conversation is not owned by the authenticated user");
        }
    }

    private boolean checkpointEnabled(AgenticRagRequest request) {
        return checkpointService != null && memoryProperties != null && memoryProperties.isEnabled()
                && request.userId() != null && !request.userId().isBlank()
                && request.conversationId() != null && request.conversationId() > 0;
    }

    private AgentCheckpoint saveCheckpoint(AgenticRagRequest request, String runId, int sequence,
                                           String node, RagState state, AgentRunContext context,
                                           AgentCheckpoint previous) {
        if (!checkpointEnabled(request)) return previous;
        CheckpointState checkpointState = checkpointState(node, state, context);
        if (previous == null) {
            return checkpointService.create(new CheckpointService.CreateCommand(
                    request.userId(), runId, request.conversationId(), AgentCheckpoint.Type.AGENTIC_RAG,
                    node, checkpointState, AgentCheckpoint.Status.RUNNING, sequence, null, null,
                    "agentic-rag", "run started", "agentic-rag:" + runId + ":start"));
        }
        return checkpointService.updateProgress(
                request.userId(), previous.id(), previous.version(), node, checkpointState, sequence,
                "agentic-rag", "node advanced", "agentic-rag:" + runId + ":" + sequence);
    }

    private void completeCheckpoint(AgenticRagRequest request, AgentCheckpoint checkpoint,
                                    RagState state, AgentRunContext context, String node) {
        if (!checkpointEnabled(request) || checkpoint == null) return;
        checkpointService.transition(request.userId(), checkpoint.id(), checkpoint.version(),
                AgentCheckpoint.Status.COMPLETED, node, checkpointState(node, state, context), null, null,
                "agentic-rag", "completed", "agentic-rag:" + checkpoint.runId());
    }

    private void failCheckpoint(AgenticRagRequest request, AgentCheckpoint checkpoint,
                                RagState state, AgentRunContext context, String errorCode) {
        if (!checkpointEnabled(request) || checkpoint == null) return;
        try {
            checkpointService.transition(request.userId(), checkpoint.id(), checkpoint.version(),
                    AgentCheckpoint.Status.FAILED, checkpoint.nodeName(),
                    checkpointState(checkpoint.nodeName(), state, context),
                    errorCode, null, "agentic-rag", "failed", "agentic-rag:" + checkpoint.runId());
        } catch (RuntimeException failure) {
            log.warn("[AgenticRAG] checkpoint failure write failed: {}", failure.getMessage());
        }
    }

    private CheckpointState checkpointState(String nextNode, RagState state, AgentRunContext context) {
        AgenticRagCheckpointSnapshot snapshot = AgenticRagCheckpointSnapshot.capture(nextNode, state, context);
        return new CheckpointState(1, nextNode, List.of(), List.of(nextNode), List.of(),
                Map.of("rounds", String.valueOf(state.getRounds().size()),
                        "rewriteCount", String.valueOf(state.getRewriteCount())),
                objectMapper.valueToTree(snapshot));
    }
}

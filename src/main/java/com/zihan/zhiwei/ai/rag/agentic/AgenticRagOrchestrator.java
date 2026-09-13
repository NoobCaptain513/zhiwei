package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.ai.agent.runtime.AgentReliabilityProperties;
import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;
import com.zihan.zhiwei.ai.agent.runtime.TokenBudgetExceededException;
import com.zihan.zhiwei.ai.memory.CheckpointService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
public class AgenticRagOrchestrator {

    private final QueryClassifier classifier;
    private final QueryPlanner planner;
    private final Retriever retriever;
    private final EvidenceGrader evidenceGrader;
    private final FeedbackQueryRewriter queryRewriter;
    private final AnswerGenerator answerGenerator;
    private final int maxRewriteAttempts;
    private final AgentNodeObserver observer;
    private final AgentReliabilityProperties reliabilityProperties;

    @Autowired(required = false)
    private CheckpointService checkpointService;
    @Autowired(required = false)
    private MemoryProperties memoryProperties;

    @Autowired
    public AgenticRagOrchestrator(
            QueryClassifier classifier,
            QueryPlanner planner,
            Retriever retriever,
            EvidenceGrader evidenceGrader,
            FeedbackQueryRewriter queryRewriter,
            AnswerGenerator answerGenerator,
            @Value("${zhiwei.ai.rag.agentic.max-rewrite-attempts:2}") int maxRewriteAttempts,
            AgentNodeObserver observer,
            AgentReliabilityProperties reliabilityProperties) {
        this.classifier = classifier;
        this.planner = planner;
        this.retriever = retriever;
        this.evidenceGrader = evidenceGrader;
        this.queryRewriter = queryRewriter;
        this.answerGenerator = answerGenerator;
        this.maxRewriteAttempts = Math.max(0, maxRewriteAttempts);
        this.observer = observer;
        this.reliabilityProperties = reliabilityProperties;
    }

    public AgenticRagOrchestrator(
            QueryClassifier classifier,
            QueryPlanner planner,
            Retriever retriever,
            EvidenceGrader evidenceGrader,
            FeedbackQueryRewriter queryRewriter,
            AnswerGenerator answerGenerator,
            int maxRewriteAttempts) {
        this(classifier, planner, retriever, evidenceGrader, queryRewriter, answerGenerator,
                maxRewriteAttempts,
                new AgentNodeObserver(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new AgentReliabilityProperties());
    }

    public AgenticRagResult execute(AgenticRagRequest request) {
        AgentRunContext context = request.runContext() == null
                ? new AgentRunContext(reliabilityProperties.newBudget(), observer)
                : request.runContext();
        AgenticRagRequest effectiveRequest = request.runContext() == null
                ? request.withRunContext(context) : request;
        RagState state = RagState.initial(effectiveRequest);
        AgentCheckpoint checkpoint = null;
        String runId = UUID.randomUUID().toString();
        int sequence = 0;
        try {
            state.setClassification(context.observe("classify", () -> classifier.classify(effectiveRequest)));
            if (!state.getClassification().needRag()) {
                return AgenticRagResult.notRequired();
            }
            checkpoint = saveCheckpoint(effectiveRequest, runId, sequence++, "classify", state, null);

            state.setPlan(context.observe("plan", () -> planner.plan(state)));
            checkpoint = saveCheckpoint(effectiveRequest, runId, sequence++, "plan", state, checkpoint);
            while (true) {
                state.addRound(context.observe("retrieve", () -> retriever.retrieve(state)));
                checkpoint = saveCheckpoint(effectiveRequest, runId, sequence++, "retrieve", state, checkpoint);
                state.setLatestGrade(context.observe("grade", () -> evidenceGrader.grade(state)));
                checkpoint = saveCheckpoint(effectiveRequest, runId, sequence++, "grade", state, checkpoint);

                if (state.getLatestGrade().sufficient()) {
                    AgenticRagResult result = context.observe("generate", () -> answerGenerator.generateAndVerify(state));
                    completeCheckpoint(effectiveRequest, checkpoint, state, "generate");
                    return result;
                }
                if (state.getLatestGrade().nextAction()
                        == com.zihan.zhiwei.ai.rag.agentic.model.NextAction.ABSTAIN
                        || state.getRewriteCount() >= maxRewriteAttempts) {
                    AgenticRagResult result = context.observe("abstain", () -> answerGenerator.abstain(state));
                    completeCheckpoint(effectiveRequest, checkpoint, state, "abstain");
                    return result;
                }

                state.setPlan(context.observe("rewrite", () -> queryRewriter.rewrite(state)));
                state.incrementRewriteCount();
                checkpoint = saveCheckpoint(effectiveRequest, runId, sequence++, "rewrite", state, checkpoint);
                log.info("[AgenticRAG] retry={} action={} gaps={}",
                        state.getRewriteCount(), state.getLatestGrade().nextAction(),
                        state.getLatestGrade().gaps());
            }
        } catch (TokenBudgetExceededException e) {
            log.warn("[AgenticRAG] terminated reason={} usedTokens={} rounds={}",
                    e.reason(), context.totalTokens(), state.getRounds().size());
            failCheckpoint(effectiveRequest, checkpoint, state, e.reason());
            String answer = "Agent 执行预算已耗尽，已停止继续调用。";
            if ("DEADLINE_EXCEEDED".equals(e.reason())) {
                answer = "Agent 执行超时，已停止继续调用。";
            }
            return new AgenticRagResult(true, answer, java.util.List.of(), state.getRounds().size(),
                    false, false, e.reason(), "system", "none",
                    context.promptTokens(), context.completionTokens(), context.totalTokens(),
                    true, 0L);
        } catch (RuntimeException e) {
            failCheckpoint(effectiveRequest, checkpoint, state, e.getClass().getSimpleName());
            throw e;
        }
    }

    private boolean checkpointEnabled(AgenticRagRequest request) {
        return checkpointService != null && memoryProperties != null && memoryProperties.isEnabled()
                && request.userId() != null && !request.userId().isBlank()
                && request.conversationId() != null && request.conversationId() > 0;
    }

    private AgentCheckpoint saveCheckpoint(AgenticRagRequest request, String runId, int sequence,
                                           String node, RagState state, AgentCheckpoint previous) {
        if (!checkpointEnabled(request)) return previous;
        try {
            CheckpointState checkpointState = checkpointState(node, state);
            if (previous != null && previous.status() == AgentCheckpoint.Status.RUNNING) {
                // Close the previous boundary before opening the next one. This prevents abandoned
                // RUNNING rows while retaining a resumable PAUSED history for every graph boundary.
                checkpointService.transition(request.userId(), previous.id(), previous.version(),
                        AgentCheckpoint.Status.PAUSED, previous.nodeName(), null, null, null,
                        "agentic-rag", "node advanced", "agentic-rag:" + runId + ":pause:" + sequence);
            }
            return checkpointService.create(new CheckpointService.CreateCommand(
                    request.userId(), runId, request.conversationId(), AgentCheckpoint.Type.AGENTIC_RAG,
                    node, checkpointState, AgentCheckpoint.Status.RUNNING, sequence, null, null,
                    "agentic-rag", "node boundary", "agentic-rag:" + runId + ":" + sequence));
        } catch (RuntimeException failure) {
            log.warn("[AgenticRAG] checkpoint write failed node={} reason={}", node, failure.getMessage());
            return previous;
        }
    }

    private void completeCheckpoint(AgenticRagRequest request, AgentCheckpoint checkpoint,
                                    RagState state, String node) {
        if (!checkpointEnabled(request) || checkpoint == null) return;
        try {
            checkpointService.transition(request.userId(), checkpoint.id(), checkpoint.version(),
                    AgentCheckpoint.Status.COMPLETED, node, checkpointState(node, state), null, null,
                    "agentic-rag", "completed", "agentic-rag:" + checkpoint.runId());
        } catch (RuntimeException failure) {
            log.warn("[AgenticRAG] checkpoint completion failed: {}", failure.getMessage());
        }
    }

    private void failCheckpoint(AgenticRagRequest request, AgentCheckpoint checkpoint,
                                RagState state, String errorCode) {
        if (!checkpointEnabled(request) || checkpoint == null) return;
        try {
            checkpointService.transition(request.userId(), checkpoint.id(), checkpoint.version(),
                    AgentCheckpoint.Status.FAILED, checkpoint.nodeName(), checkpointState(checkpoint.nodeName(), state),
                    errorCode, null, "agentic-rag", "failed", "agentic-rag:" + checkpoint.runId());
        } catch (RuntimeException failure) {
            log.warn("[AgenticRAG] checkpoint failure write failed: {}", failure.getMessage());
        }
    }

    private CheckpointState checkpointState(String node, RagState state) {
        return new CheckpointState(1, node, List.of(), List.of(node), List.of(),
                Map.of("rounds", String.valueOf(state.getRounds().size())), null);
    }
}

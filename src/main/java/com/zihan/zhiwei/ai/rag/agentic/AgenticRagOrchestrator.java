package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.ai.agent.runtime.AgentReliabilityProperties;
import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;
import com.zihan.zhiwei.ai.agent.runtime.TokenBudgetExceededException;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
        try {
            state.setClassification(context.observe("classify", () -> classifier.classify(effectiveRequest)));
            if (!state.getClassification().needRag()) {
                return AgenticRagResult.notRequired();
            }

            state.setPlan(context.observe("plan", () -> planner.plan(state)));
            while (true) {
                state.addRound(context.observe("retrieve", () -> retriever.retrieve(state)));
                state.setLatestGrade(context.observe("grade", () -> evidenceGrader.grade(state)));

                if (state.getLatestGrade().sufficient()) {
                    return context.observe("generate", () -> answerGenerator.generateAndVerify(state));
                }
                if (state.getLatestGrade().nextAction()
                        == com.zihan.zhiwei.ai.rag.agentic.model.NextAction.ABSTAIN
                        || state.getRewriteCount() >= maxRewriteAttempts) {
                    return context.observe("abstain", () -> answerGenerator.abstain(state));
                }

                state.setPlan(context.observe("rewrite", () -> queryRewriter.rewrite(state)));
                state.incrementRewriteCount();
                log.info("[AgenticRAG] retry={} action={} gaps={}",
                        state.getRewriteCount(), state.getLatestGrade().nextAction(),
                        state.getLatestGrade().gaps());
            }
        } catch (TokenBudgetExceededException e) {
            log.warn("[AgenticRAG] terminated reason={} usedTokens={} rounds={}",
                    e.reason(), context.totalTokens(), state.getRounds().size());
            String answer = "Agent 执行预算已耗尽，已停止继续调用。";
            if ("DEADLINE_EXCEEDED".equals(e.reason())) {
                answer = "Agent 执行超时，已停止继续调用。";
            }
            return new AgenticRagResult(true, answer, java.util.List.of(), state.getRounds().size(),
                    false, false, e.reason(), "system", "none",
                    context.promptTokens(), context.completionTokens(), context.totalTokens(),
                    true, 0L);
        }
    }
}

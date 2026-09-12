package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    public AgenticRagOrchestrator(
            QueryClassifier classifier,
            QueryPlanner planner,
            Retriever retriever,
            EvidenceGrader evidenceGrader,
            FeedbackQueryRewriter queryRewriter,
            AnswerGenerator answerGenerator,
            @Value("${zhiwei.ai.rag.agentic.max-rewrite-attempts:2}") int maxRewriteAttempts) {
        this.classifier = classifier;
        this.planner = planner;
        this.retriever = retriever;
        this.evidenceGrader = evidenceGrader;
        this.queryRewriter = queryRewriter;
        this.answerGenerator = answerGenerator;
        this.maxRewriteAttempts = Math.max(0, maxRewriteAttempts);
    }

    public AgenticRagResult execute(AgenticRagRequest request) {
        RagState state = RagState.initial(request);
        state.setClassification(classifier.classify(request));
        if (!state.getClassification().needRag()) {
            return AgenticRagResult.notRequired();
        }

        state.setPlan(planner.plan(state));
        while (true) {
            state.addRound(retriever.retrieve(state));
            state.setLatestGrade(evidenceGrader.grade(state));

            if (state.getLatestGrade().sufficient()) {
                return answerGenerator.generateAndVerify(state);
            }
            if (state.getLatestGrade().nextAction()
                    == com.zihan.zhiwei.ai.rag.agentic.model.NextAction.ABSTAIN
                    || state.getRewriteCount() >= maxRewriteAttempts) {
                return answerGenerator.abstain(state);
            }

            state.setPlan(queryRewriter.rewrite(state));
            state.incrementRewriteCount();
            log.info("[AgenticRAG] retry={} action={} gaps={}",
                    state.getRewriteCount(), state.getLatestGrade().nextAction(),
                    state.getLatestGrade().gaps());
        }
    }
}

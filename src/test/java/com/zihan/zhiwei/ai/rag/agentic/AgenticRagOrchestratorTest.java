package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticRagOrchestratorTest {

    @Mock private QueryClassifier classifier;
    @Mock private QueryPlanner planner;
    @Mock private Retriever retriever;
    @Mock private EvidenceGrader evidenceGrader;
    @Mock private FeedbackQueryRewriter queryRewriter;
    @Mock private AnswerGenerator answerGenerator;

    @Test
    void shouldBypassRetrievalWhenClassifierSaysRagIsNotRequired() {
        AgenticRagRequest request = new AgenticRagRequest("你好", null, null, "qwen-plus");
        when(classifier.classify(request)).thenReturn(new QueryClassification(
                false, QuestionType.CONVERSATIONAL, false, false, 0.99, "普通问候"));

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                classifier, planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        AgenticRagResult result = orchestrator.execute(request);

        assertThat(result.ragRequired()).isFalse();
        verify(planner, never()).plan(org.mockito.ArgumentMatchers.any());
        verify(retriever, never()).retrieve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRewriteAndRetrieveAgainWhenEvidenceIsInsufficient() {
        AgenticRagRequest request = new AgenticRagRequest("Redis MOVED 怎么处理", null, null, "qwen-plus");
        QueryClassification classification = new QueryClassification(
                true, QuestionType.TROUBLESHOOTING, false, true, 0.95, "需要内部处置文档");
        RetrievalPlan initialPlan = plan("MOVED 原因", 20);
        RetrievalPlan rewrittenPlan = plan("Redis 7 MOVED 标准处置步骤", 40);
        EvidenceGrade insufficient = new EvidenceGrade(false, List.of(), List.of(),
                List.of("缺少处置步骤"), List.of(), NextAction.EXPAND_RECALL, "证据不足");
        EvidenceGrade sufficient = new EvidenceGrade(true, List.of(7L), List.of("处置步骤"),
                List.of(), List.of(), NextAction.ANSWER, "证据充分");
        AgenticRagResult expected = new AgenticRagResult(true, "按手册处理 [E7]", List.of(),
                2, true, false, "ANSWERED", "test", "qwen-plus", 1, 1, 2, false, 10);

        when(classifier.classify(request)).thenReturn(classification);
        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(initialPlan);
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetrievalResult.empty(), RetrievalResult.empty());
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any()))
                .thenReturn(insufficient, sufficient);
        when(queryRewriter.rewrite(org.mockito.ArgumentMatchers.any())).thenReturn(rewrittenPlan);
        when(answerGenerator.generateAndVerify(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                classifier, planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        AgenticRagResult result = orchestrator.execute(request);

        assertThat(result).isSameAs(expected);
        verify(retriever, org.mockito.Mockito.times(2)).retrieve(org.mockito.ArgumentMatchers.any());
        verify(queryRewriter).rewrite(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAbstainImmediatelyWhenGraderRequestsAbstention() {
        AgenticRagRequest request = new AgenticRagRequest("敏感事实", null, null, "qwen-plus");
        when(classifier.classify(request)).thenReturn(new QueryClassification(
                true, QuestionType.FACTUAL, false, false, 1, "需要证据"));
        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(plan("事实", 20));
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(RetrievalResult.empty());
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any())).thenReturn(new EvidenceGrade(
                false, List.of(), List.of(), List.of("来源不可用"), List.of(),
                NextAction.ABSTAIN, "无法可靠检索"));
        AgenticRagResult expected = new AgenticRagResult(true, "证据不足", List.of(),
                1, false, false, "INSUFFICIENT_EVIDENCE", "system", "none",
                0, 0, 0, false, 1);
        when(answerGenerator.abstain(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                classifier, planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        assertThat(orchestrator.execute(request)).isSameAs(expected);
        verify(queryRewriter, never()).rewrite(org.mockito.ArgumentMatchers.any());
    }

    private static RetrievalPlan plan(String query, int candidateK) {
        return new RetrievalPlan(List.of(new com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask(
                query,
                com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource.INTERNAL_KB,
                com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy.HYBRID,
                Map.of())), 5, candidateK, "test");
    }
}

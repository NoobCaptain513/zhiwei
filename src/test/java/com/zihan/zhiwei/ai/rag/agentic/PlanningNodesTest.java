package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.QueryRewriter;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.QueryRewriteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningNodesTest {

    @Test
    void plannerShouldTurnSubQuestionsIntoRetrievalTasks() {
        QueryRewriter delegate = mock(QueryRewriter.class);
        when(delegate.rewrite("Redis MOVED 原因和处理", "history"))
                .thenReturn(new QueryRewriteResult("original", "rewritten", List.of("原因", "处理步骤")));
        RagState state = state();

        RetrievalPlan plan = new DefaultQueryPlanner(delegate, 5, 20).plan(state);

        assertThat(plan.tasks()).extracting(task -> task.subQuestion())
                .containsExactly("原因", "处理步骤");
        assertThat(plan.candidateK()).isEqualTo(20);
    }

    @Test
    void plannerShouldChooseStrategyFromQueryWithoutAClassifierResult() {
        QueryRewriter delegate = mock(QueryRewriter.class);
        when(delegate.rewrite("Redis MOVED 报错怎么处理", null))
                .thenReturn(new QueryRewriteResult("original", "rewritten", List.of("处理步骤")));
        RagState state = RagState.initial(new AgenticRagRequest(
                "Redis MOVED 报错怎么处理", null, null, "qwen-plus"));

        RetrievalPlan plan = new DefaultQueryPlanner(delegate, 5, 20).plan(state);

        assertThat(plan.tasks()).extracting(RetrievalTask::strategy)
                .containsOnly(RetrievalStrategy.KEYWORD_HEAVY);
    }

    @Test
    void feedbackRewriterShouldExpandRecallAndIncludeEvidenceGaps() {
        QueryRewriter delegate = mock(QueryRewriter.class);
        when(delegate.rewrite(contains("缺少生产处置步骤"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new QueryRewriteResult("original", "Redis 7 MOVED 生产处置步骤", List.of()));
        RagState state = state();
        state.setPlan(new RetrievalPlan(List.of(new RetrievalTask("原问题",
                KnowledgeSource.INTERNAL_KB, RetrievalStrategy.HYBRID, Map.of())), 5, 20, "initial"));
        state.setLatestGrade(new EvidenceGrade(false, List.of(), List.of(),
                List.of("缺少生产处置步骤"), List.of(), NextAction.EXPAND_RECALL, "不足"));

        RetrievalPlan rewritten = new DefaultFeedbackQueryRewriter(delegate, 100).rewrite(state);

        assertThat(rewritten.candidateK()).isEqualTo(40);
        assertThat(rewritten.tasks().getFirst().subQuestion()).isEqualTo("Redis 7 MOVED 生产处置步骤");
    }

    @Test
    void feedbackRewriterShouldNotRepeatThePreviousQuery() {
        QueryRewriter delegate = mock(QueryRewriter.class);
        when(delegate.rewrite(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new QueryRewriteResult("original", "原问题", List.of()));
        RagState state = state();
        state.setPlan(new RetrievalPlan(List.of(new RetrievalTask("原问题",
                KnowledgeSource.INTERNAL_KB, RetrievalStrategy.HYBRID, Map.of())), 5, 20, "initial"));
        state.setLatestGrade(new EvidenceGrade(false, List.of(), List.of(),
                List.of("缺少版本信息"), List.of(), NextAction.REWRITE, "不足"));

        RetrievalPlan rewritten = new DefaultFeedbackQueryRewriter(delegate, 100).rewrite(state);

        assertThat(rewritten.tasks().getFirst().subQuestion()).isNotEqualTo("原问题");
        assertThat(rewritten.tasks().getFirst().subQuestion()).contains("缺少版本信息");
    }

    private static RagState state() {
        RagState state = RagState.initial(new AgenticRagRequest(
                "Redis MOVED 原因和处理", "history", null, "qwen-plus"));
        state.setClassification(new QueryClassification(
                true, QuestionType.TROUBLESHOOTING, false, true, 1, "test"));
        return state;
    }
}

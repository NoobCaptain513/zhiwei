package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.QueryRewriter;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.QueryRewriteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
public class DefaultFeedbackQueryRewriter implements FeedbackQueryRewriter {

    private final QueryRewriter queryRewriter;
    private final int maxCandidateK;

    public DefaultFeedbackQueryRewriter(
            QueryRewriter queryRewriter,
            @Value("${zhiwei.ai.rag.agentic.max-candidate-k:100}") int maxCandidateK) {
        this.queryRewriter = queryRewriter;
        this.maxCandidateK = Math.max(1, maxCandidateK);
    }

    @Override
    public RetrievalPlan rewrite(RagState state) {
        String gaps = String.join("；", state.getLatestGrade().gaps());
        String feedbackQuery = state.getRequest().query()
                + (gaps.isBlank() ? "" : "。需要补充检索：" + gaps);
        QueryRewriteResult rewrite = state.getRequest().runContext() == null
                ? queryRewriter.rewrite(feedbackQuery, state.getRequest().historyContext())
                : queryRewriter.rewrite(feedbackQuery, state.getRequest().historyContext(),
                        state.getRequest().runContext(), "rewrite");

        RetrievalStrategy strategy = nextStrategy(
                state.getLatestGrade().nextAction(), state.getPlan().tasks());
        List<String> queries = rewrite.allQueries();
        if (queries.isEmpty()) {
            queries = List.of(feedbackQuery);
        }
        java.util.Set<String> previousQueries = state.getPlan().tasks().stream()
                .map(RetrievalTask::subQuestion)
                .collect(java.util.stream.Collectors.toSet());
        queries = queries.stream()
                .map(query -> previousQueries.contains(query) ? feedbackQuery : query)
                .distinct()
                .toList();
        List<RetrievalTask> tasks = queries.stream()
                .map(query -> new RetrievalTask(query, KnowledgeSource.INTERNAL_KB, strategy,
                        Map.of("retry", state.getRewriteCount() + 1)))
                .toList();
        int candidateK = state.getLatestGrade().nextAction() == NextAction.EXPAND_RECALL
                ? Math.min(maxCandidateK, state.getPlan().candidateK() * 2)
                : state.getPlan().candidateK();
        return new RetrievalPlan(tasks, state.getPlan().topK(), candidateK,
                "依据证据缺口调整检索：" + state.getLatestGrade().reason());
    }

    private static RetrievalStrategy nextStrategy(NextAction action, List<RetrievalTask> previous) {
        RetrievalStrategy current = previous.isEmpty()
                ? RetrievalStrategy.HYBRID : previous.getFirst().strategy();
        if (action != NextAction.SWITCH_STRATEGY) {
            return current;
        }
        return switch (current) {
            case HYBRID, VECTOR_HEAVY -> RetrievalStrategy.KEYWORD_HEAVY;
            case KEYWORD_HEAVY -> RetrievalStrategy.VECTOR_HEAVY;
        };
    }
}

package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.QueryRewriter;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
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
public class DefaultQueryPlanner implements QueryPlanner {

    private final QueryRewriter queryRewriter;
    private final int topK;
    private final int candidateK;

    public DefaultQueryPlanner(QueryRewriter queryRewriter,
                               @Value("${zhiwei.ai.rag.top-k:5}") int topK,
                               @Value("${zhiwei.ai.rag.candidate-k:20}") int candidateK) {
        this.queryRewriter = queryRewriter;
        this.topK = Math.max(1, topK);
        this.candidateK = Math.max(this.topK, candidateK);
    }

    @Override
    public RetrievalPlan plan(RagState state) {
        QueryRewriteResult rewrite = queryRewriter.rewrite(
                state.getRequest().query(), state.getRequest().historyContext());
        RetrievalStrategy strategy = chooseStrategy(state.getClassification().questionType());
        List<RetrievalTask> tasks = rewrite.allQueries().stream()
                .map(query -> new RetrievalTask(query, KnowledgeSource.INTERNAL_KB, strategy, Map.of()))
                .toList();
        return new RetrievalPlan(tasks, topK, candidateK, "首轮查询分解与混合召回");
    }

    private static RetrievalStrategy chooseStrategy(QuestionType type) {
        return switch (type) {
            case TROUBLESHOOTING, HOW_TO -> RetrievalStrategy.KEYWORD_HEAVY;
            case SUMMARY -> RetrievalStrategy.VECTOR_HEAVY;
            default -> RetrievalStrategy.HYBRID;
        };
    }
}

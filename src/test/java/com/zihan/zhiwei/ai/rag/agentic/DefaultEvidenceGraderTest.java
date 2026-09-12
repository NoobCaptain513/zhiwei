package com.zihan.zhiwei.ai.rag.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultEvidenceGraderTest {

    @Test
    void shouldGradeSufficiencyConflictsAndRejectUnknownEvidenceIds() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        when(router.chatWithFailover(any())).thenReturn(new ProviderChatResponse("""
                {"sufficient":true,"acceptedEvidenceIds":[1,999],
                 "coveredSubQuestions":["原因"],"gaps":[],
                 "conflicts":[{"leftEvidenceId":1,"rightEvidenceId":2,"topic":"重启","description":"建议冲突"}],
                 "nextAction":"ANSWER","reason":"可以回答"}
                """, "qwen", "test", 1, 1, 2));
        RagState state = stateWithHits(List.of(hit(1), hit(2)));

        var grade = new DefaultEvidenceGrader(router, new ObjectMapper(), "qwen-plus", 1).grade(state);

        assertThat(grade.sufficient()).isTrue();
        assertThat(grade.acceptedEvidenceIds()).containsExactly(1L);
        assertThat(grade.conflicts()).hasSize(1);
    }

    private static RagState stateWithHits(List<RagHit> hits) {
        RagState state = RagState.initial(new AgenticRagRequest("Redis 问题", null, null, "qwen-plus"));
        state.setClassification(new QueryClassification(true, QuestionType.FACTUAL, false, false, 1, "test"));
        state.setPlan(new RetrievalPlan(List.of(new RetrievalTask("原因", KnowledgeSource.INTERNAL_KB,
                RetrievalStrategy.HYBRID, Map.of())), 5, 20, "test"));
        state.addRound(new RetrievalResult(hits, 1));
        return state;
    }

    private static RagHit hit(long id) {
        return new RagHit(new KnowledgeChunk(id, 1L, "src", "title", "content " + id, 1, null), .8, .2, .8);
    }
}

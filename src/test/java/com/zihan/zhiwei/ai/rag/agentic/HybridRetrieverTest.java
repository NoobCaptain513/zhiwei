package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {

    @Test
    void shouldExecuteAllTasksAndDeduplicateChunksByBestScore() {
        AiRagService ragService = mock(AiRagService.class);
        RagHit weak = hit(1L, 0.2);
        RagHit strong = hit(1L, 0.8);
        RagHit other = hit(2L, 0.6);
        when(ragService.searchRaw("原因", "native", 5, 20, 1.0, 0.5)).thenReturn(List.of(weak, other));
        when(ragService.searchRaw("步骤", "native", 5, 20, 1.0, 0.5)).thenReturn(List.of(strong));

        RagState state = RagState.initial(new AgenticRagRequest("问题", null, "native", "qwen-plus"));
        state.setClassification(new QueryClassification(true, QuestionType.HOW_TO, false, true, 1, "test"));
        state.setPlan(new RetrievalPlan(List.of(task("原因"), task("步骤")), 5, 20, "test"));

        var result = new HybridRetriever(ragService).retrieve(state);

        assertThat(result.hits()).extracting(hit -> hit.chunk().id()).containsExactly(1L, 2L);
        assertThat(result.hits().getFirst().finalScore()).isEqualTo(0.8);
    }

    @Test
    void shouldApplyKeywordHeavyWeightsFromPlan() {
        AiRagService ragService = mock(AiRagService.class);
        when(ragService.searchRaw("错误码", null, 5, 20, 0.5, 1.5)).thenReturn(List.of());
        RagState state = RagState.initial(new AgenticRagRequest("问题", null, null, "qwen-plus"));
        state.setPlan(new RetrievalPlan(List.of(new RetrievalTask("错误码", KnowledgeSource.INTERNAL_KB,
                RetrievalStrategy.KEYWORD_HEAVY, Map.of())), 5, 20, "test"));

        new HybridRetriever(ragService).retrieve(state);

        verify(ragService).searchRaw("错误码", null, 5, 20, 0.5, 1.5);
    }

    @Test
    void shouldRunIndependentReadOnlyRetrievalTasksInParallel() {
        AiRagService ragService = mock(AiRagService.class);
        when(ragService.searchRaw(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(invocation -> {
            Thread.sleep(120);
            return List.of();
        });
        RagState state = RagState.initial(new AgenticRagRequest("问题", null, null, "qwen-plus"));
        state.setPlan(new RetrievalPlan(List.of(task("问题一"), task("问题二")), 5, 20, "test"));
        long started = System.nanoTime();

        new HybridRetriever(ragService).retrieve(state);

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(220);
    }

    private static RetrievalTask task(String query) {
        return new RetrievalTask(query, KnowledgeSource.INTERNAL_KB, RetrievalStrategy.HYBRID, Map.of());
    }

    private static RagHit hit(long id, double score) {
        return new RagHit(new KnowledgeChunk(id, 10L, "src-" + id, "title", "content", 10, null),
                score, 0, score);
    }
}

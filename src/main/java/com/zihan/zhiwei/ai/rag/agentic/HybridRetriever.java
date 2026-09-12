package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class HybridRetriever implements Retriever {

    private final AiRagService ragService;

    @Override
    public RetrievalResult retrieve(RagState state) {
        long startedAt = System.currentTimeMillis();
        Map<Long, RagHit> unique = new LinkedHashMap<>();

        for (RetrievalTask task : state.getPlan().tasks()) {
            if (task.source() != KnowledgeSource.INTERNAL_KB) {
                log.warn("[AgenticRAG] unsupported source={}, skip task='{}'",
                        task.source(), task.subQuestion());
                continue;
            }
            double[] weights = weights(task);
            List<RagHit> hits = ragService.searchRaw(
                    task.subQuestion(),
                    state.getRequest().preferredProvider(),
                    state.getPlan().topK(),
                    state.getPlan().candidateK(),
                    weights[0], weights[1]);
            for (RagHit hit : hits) {
                unique.merge(hit.chunk().id(), hit,
                        (left, right) -> left.finalScore() >= right.finalScore() ? left : right);
            }
        }

        List<RagHit> merged = unique.values().stream()
                .sorted(Comparator.comparingDouble(RagHit::finalScore).reversed())
                .limit(state.getPlan().topK())
                .toList();
        return new RetrievalResult(merged, System.currentTimeMillis() - startedAt);
    }

    private static double[] weights(RetrievalTask task) {
        return switch (task.strategy()) {
            case HYBRID -> new double[]{1.0, 0.5};
            case VECTOR_HEAVY -> new double[]{1.5, 0.3};
            case KEYWORD_HEAVY -> new double[]{0.5, 1.5};
        };
    }
}

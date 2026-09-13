package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
public class HybridRetriever implements Retriever {

    private final AiRagService ragService;
    private final long taskTimeoutMs;

    @Autowired
    public HybridRetriever(AiRagService ragService,
                           @Value("${zhiwei.ai.rag.sub-query-timeout-ms:8000}") long taskTimeoutMs) {
        this.ragService = ragService;
        this.taskTimeoutMs = Math.max(1L, taskTimeoutMs);
    }

    public HybridRetriever(AiRagService ragService) {
        this(ragService, 8_000L);
    }

    @Override
    public RetrievalResult retrieve(RagState state) {
        long startedAt = System.currentTimeMillis();
        Map<Long, RagHit> unique = new LinkedHashMap<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<List<RagHit>>> futures = new ArrayList<>();
        try {
            for (RetrievalTask task : state.getPlan().tasks()) {
                futures.add(executor.submit(() -> retrieveTask(state, task)));
            }
            long contextRemaining = state.getRequest().runContext() == null
                    ? taskTimeoutMs : state.getRequest().runContext().remainingDurationMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, Math.min(taskTimeoutMs, contextRemaining)));
            for (int i = 0; i < futures.size(); i++) {
                try {
                    long remainingNanos = Math.max(1L, deadline - System.nanoTime());
                    List<RagHit> hits = futures.get(i).get(remainingNanos, TimeUnit.NANOSECONDS);
                    for (RagHit hit : hits) {
                        unique.merge(hit.chunk().id(), hit,
                                (left, right) -> left.finalScore() >= right.finalScore() ? left : right);
                    }
                } catch (Exception e) {
                    futures.get(i).cancel(true);
                    log.warn("[AgenticRAG] retrieval task failed index={}: {}", i, e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        List<RagHit> merged = unique.values().stream()
                .sorted(Comparator.comparingDouble(RagHit::finalScore).reversed())
                .limit(state.getPlan().topK())
                .toList();
        return new RetrievalResult(merged, System.currentTimeMillis() - startedAt);
    }

    private List<RagHit> retrieveTask(RagState state, RetrievalTask task) {
        if (task.source() != KnowledgeSource.INTERNAL_KB) {
            log.warn("[AgenticRAG] unsupported source={}, skip task='{}'",
                    task.source(), task.subQuestion());
            return List.of();
        }
        double[] weights = weights(task);
        return ragService.searchRaw(
                task.subQuestion(),
                state.getRequest().preferredProvider(),
                state.getPlan().topK(),
                state.getPlan().candidateK(),
                weights[0], weights[1]);
    }

    private static double[] weights(RetrievalTask task) {
        return switch (task.strategy()) {
            case HYBRID -> new double[]{1.0, 0.5};
            case VECTOR_HEAVY -> new double[]{1.5, 0.3};
            case KEYWORD_HEAVY -> new double[]{0.5, 1.5};
        };
    }
}

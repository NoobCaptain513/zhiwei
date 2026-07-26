package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * D31: 多查询结果聚合器。
 * 子问题各自检索后，合并去重，多路命中的 chunk 提权。
 */
@Component
public class MultiQueryAggregator {

    /**
     * 聚合多路检索结果。
     */
    public List<RagHit> aggregate(Map<String, List<RagHit>> resultsMap, int topK) {
        Map<Long, Accumulator> accumulators = new LinkedHashMap<>();

        for (Map.Entry<String, List<RagHit>> entry : resultsMap.entrySet()) {
            String query = entry.getKey();
            List<RagHit> hits = entry.getValue();

            for (int i = 0; i < hits.size(); i++) {
                RagHit hit = hits.get(i);
                long chunkId = hit.chunk().id();
                int rank = i + 1;

                accumulators.computeIfAbsent(chunkId,
                        k -> new Accumulator(hit.chunk(), hit.vectorScore(), hit.lexicalScore()))
                        .addScore(hit.finalScore(), rank, query);
            }
        }

        return accumulators.values().stream()
                .map(Accumulator::toRagHit)
                .sorted(Comparator.comparingDouble(RagHit::finalScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private static class Accumulator {
        private final com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk chunk;
        private final double vectorScore;
        private final double lexicalScore;
        private double scoreSum;
        private int hitCount;

        Accumulator(com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk chunk,
                    double vectorScore, double lexicalScore) {
            this.chunk = chunk;
            this.vectorScore = vectorScore;
            this.lexicalScore = lexicalScore;
        }

        void addScore(double score, int rank, String query) {
            double rankScore = 10.0 / (60.0 + rank);
            scoreSum += score + rankScore;
            hitCount++;
            // P3-25 修复：删除只写不读的 hitBy 字段
        }

        RagHit toRagHit() {
            double bonus = Math.log(1 + hitCount);
            double finalScore = scoreSum * (1 + bonus * 0.3);
            return new RagHit(chunk, vectorScore, lexicalScore, finalScore);
        }
    }
}

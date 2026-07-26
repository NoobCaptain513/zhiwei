package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D30: RRF (Reciprocal Rank Fusion) 多通道检索融合。
 *
 * 公式: RRF(d) = Σ w_i / (k + rank_i(d))
 * k 越大，排名间差异越平滑。
 */
@Component
public class RrfFusion {

    /**
     * 融合多个排序列表。
     *
     * @param vectorRanked  向量检索结果（按余弦相似度降序）
     * @param keywordRanked 关键词检索结果（按匹配度降序）
     * @param k             RRF 常数，默认 60
     * @param vectorWeight  向量通道权重
     * @param keywordWeight 关键词通道权重
     * @param topK          最终返回数量
     */
    public List<FusedHit> fuse(
            List<PgVectorKnowledgeRepository.ScoredChunk> vectorRanked,
            List<PgVectorKnowledgeRepository.ScoredChunk> keywordRanked,
            double k,
            double vectorWeight,
            double keywordWeight,
            int topK) {

        Map<Long, KnowledgeChunk> chunkMap = new LinkedHashMap<>();
        Map<Long, Double> vectorScores = new LinkedHashMap<>();
        Map<Long, Double> keywordScores = new LinkedHashMap<>();
        Map<Long, Double> rrfAccum = new LinkedHashMap<>();

        int rank = 1;
        for (PgVectorKnowledgeRepository.ScoredChunk item : vectorRanked) {
            long id = item.chunk().id();
            chunkMap.putIfAbsent(id, item.chunk());
            vectorScores.putIfAbsent(id, item.vectorScore());
            rrfAccum.merge(id, vectorWeight / (k + rank), Double::sum);
            rank++;
        }

        rank = 1;
        for (PgVectorKnowledgeRepository.ScoredChunk item : keywordRanked) {
            long id = item.chunk().id();
            chunkMap.putIfAbsent(id, item.chunk());
            keywordScores.putIfAbsent(id, item.vectorScore());
            rrfAccum.merge(id, keywordWeight / (k + rank), Double::sum);
            rank++;
        }

        List<FusedHit> hits = new ArrayList<>();
        rrfAccum.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .forEachOrdered(e -> {
                    long id = e.getKey();
                    hits.add(new FusedHit(
                            chunkMap.get(id),
                            vectorScores.getOrDefault(id, -1.0),
                            keywordScores.getOrDefault(id, -1.0),
                            e.getValue()
                    ));
                });

        return hits;
    }

    public record FusedHit(
            KnowledgeChunk chunk,
            double vectorScore,
            double keywordScore,
            double rrfScore
    ) {}
}

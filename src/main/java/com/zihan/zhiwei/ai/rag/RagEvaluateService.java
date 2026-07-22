package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.pojo.dto.RagEvaluateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 质量评估：准确率 / 召回率 / A/B 对比。
 * 按 (query → expectedSourceId) 对评估集逐条检索打分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluateService {

    private final AiRagService aiRagService;

    /**
     * 评估单组查询集。
     *
     * @param dataset   数据集名称
     * @param evalItems 查询列表，每项 { query, expectedSourceId }
     * @param topK      检索 topK
     * @param candidateK 候选召回数
     */
    public RagEvaluateResponse evaluate(
            String dataset,
            List<EvalItem> evalItems,
            int topK,
            int candidateK) {

        List<RagEvaluateResponse.QueryDetail> details = new ArrayList<>();
        int hitCount = 0;
        double totalRank = 0;
        double totalScore = 0;
        int rankedCount = 0;

        for (EvalItem item : evalItems) {
            List<RagHit> hits = aiRagService.search(item.query(), topK, candidateK);

            boolean hit = false;
            int topRank = -1;
            double finalScore = 0;
            String matchedSource = null;

            for (int i = 0; i < hits.size(); i++) {
                RagHit hitObj = hits.get(i);
                String sourceId = hitObj.chunk().sourceId();
                if (sourceId != null && sourceId.equals(item.expectedSourceId())) {
                    hit = true;
                    topRank = i + 1;
                    finalScore = hitObj.finalScore();
                    matchedSource = sourceId;
                    break;
                }
            }

            if (hit) {
                hitCount++;
                totalRank += topRank;
                rankedCount++;
            }
            totalScore += hits.isEmpty() ? 0 : hits.get(0).finalScore();

            details.add(RagEvaluateResponse.QueryDetail.builder()
                    .query(item.query())
                    .hit(hit)
                    .topRank(topRank)
                    .finalScore(finalScore)
                    .matchedSource(matchedSource)
                    .expectedSource(item.expectedSourceId())
                    .build());
        }

        int total = evalItems.size();
        return RagEvaluateResponse.builder()
                .dataset(dataset)
                .totalQueries(total)
                .hitCount(hitCount)
                .recallRate(total > 0 ? (double) hitCount / total : 0)
                .avgRank(rankedCount > 0 ? totalRank / rankedCount : 0)
                .avgFinalScore(total > 0 ? totalScore / total : 0)
                .details(details)
                .build();
    }

    /**
     * A/B 对比：同一评估集，两组参数分别检索，比较召回率和平均排名。
     */
    public RagEvaluateResponse.AbComparison compareAb(
            List<EvalItem> evalItems,
            String variantA, int topKA, int candidateKA,
            String variantB, int topKB, int candidateKB) {

        RagEvaluateResponse resultA = evaluate("A", evalItems, topKA, candidateKA);
        RagEvaluateResponse resultB = evaluate("B", evalItems, topKB, candidateKB);

        String winner;
        if (resultA.getRecallRate() > resultB.getRecallRate()) {
            winner = variantA;
        } else if (resultB.getRecallRate() > resultA.getRecallRate()) {
            winner = variantB;
        } else {
            winner = resultA.getAvgRank() <= resultB.getAvgRank() ? variantA : variantB;
        }

        return RagEvaluateResponse.AbComparison.builder()
                .variantA(variantA)
                .variantB(variantB)
                .recallA(resultA.getRecallRate())
                .recallB(resultB.getRecallRate())
                .avgRankA(resultA.getAvgRank())
                .avgRankB(resultB.getAvgRank())
                .winner(winner)
                .build();
    }

    /** 评估集条目 */
    public record EvalItem(String query, String expectedSourceId) {}
}
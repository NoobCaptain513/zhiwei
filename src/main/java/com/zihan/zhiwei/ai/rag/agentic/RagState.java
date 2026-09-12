package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public final class RagState {

    private final AgenticRagRequest request;
    private QueryClassification classification;
    private RetrievalPlan plan;
    private EvidenceGrade latestGrade;
    private int rewriteCount;
    private final List<RetrievalResult> rounds = new ArrayList<>();

    private RagState(AgenticRagRequest request) {
        this.request = request;
    }

    public static RagState initial(AgenticRagRequest request) {
        return new RagState(request);
    }

    public void setClassification(QueryClassification classification) {
        this.classification = classification;
    }

    public void setPlan(RetrievalPlan plan) {
        this.plan = plan;
    }

    public void setLatestGrade(EvidenceGrade latestGrade) {
        this.latestGrade = latestGrade;
    }

    public void addRound(RetrievalResult result) {
        rounds.add(result);
    }

    public RetrievalResult latestRetrieval() {
        return rounds.isEmpty() ? RetrievalResult.empty() : rounds.getLast();
    }

    public List<RagHit> allHits() {
        Map<Long, RagHit> unique = new LinkedHashMap<>();
        rounds.forEach(round -> round.hits().forEach(hit -> unique.merge(hit.chunk().id(), hit,
                (left, right) -> left.finalScore() >= right.finalScore() ? left : right)));
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(RagHit::finalScore).reversed())
                .toList();
    }

    public void incrementRewriteCount() {
        rewriteCount++;
    }
}

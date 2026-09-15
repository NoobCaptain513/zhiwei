package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagStateTest {

    @Test
    void shouldAccumulateEvidenceAcrossRetrievalRoundsAndKeepBestDuplicate() {
        RagState state = RagState.initial(new AgenticRagRequest("q", null, null, "m"));
        state.addRound(new RetrievalResult(List.of(hit(1, .4), hit(2, .5)), 1));
        state.addRound(new RetrievalResult(List.of(hit(1, .9), hit(3, .7)), 1));

        assertThat(state.allHits()).extracting(hit -> hit.chunk().id()).containsExactly(1L, 3L, 2L);
        assertThat(state.allHits().getFirst().finalScore()).isEqualTo(.9);
    }

    @Test
    void shouldRestoreEveryDecisionFieldAndRetrievalRound() {
        AgenticRagRequest request = new AgenticRagRequest("q", "history", null, "m");
        QueryClassification classification = new QueryClassification(
                true, QuestionType.FACTUAL, false, false, 0.9, "reason");
        RetrievalPlan plan = new RetrievalPlan(List.of(), 5, 20, "plan");
        RetrievalResult round = new RetrievalResult(List.of(hit(1, .8)), 10);
        EvidenceGrade grade = new EvidenceGrade(false, List.of(1L), List.of(), List.of("gap"),
                List.of(), NextAction.REWRITE, "insufficient");

        RagState restored = RagState.restore(
                request, classification, plan, grade, 1, List.of(round));

        assertThat(restored.getRequest()).isEqualTo(request);
        assertThat(restored.getClassification()).isEqualTo(classification);
        assertThat(restored.getPlan()).isEqualTo(plan);
        assertThat(restored.getLatestGrade()).isEqualTo(grade);
        assertThat(restored.getRewriteCount()).isEqualTo(1);
        assertThat(restored.getRounds()).containsExactly(round);
    }

    private static RagHit hit(long id, double score) {
        return new RagHit(new KnowledgeChunk(id, 1L, "s", "t", "c", 1, null), score, 0, score);
    }
}

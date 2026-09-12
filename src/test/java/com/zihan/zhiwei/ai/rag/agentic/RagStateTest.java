package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
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

    private static RagHit hit(long id, double score) {
        return new RagHit(new KnowledgeChunk(id, 1L, "s", "t", "c", 1, null), score, 0, score);
    }
}

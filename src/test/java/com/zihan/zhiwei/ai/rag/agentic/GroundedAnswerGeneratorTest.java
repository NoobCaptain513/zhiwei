package com.zihan.zhiwei.ai.rag.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundedAnswerGeneratorTest {

    @Test
    void shouldReturnOnlyValidatedCitations() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        ProviderChatResponse generated = new ProviderChatResponse(
                "应按照运行手册执行 [E1]。", "qwen-plus", "test", 10, 5, 15);
        when(router.executeWithFailover(isNull(), any())).thenReturn(
                new FailoverResult(generated, "test", "test", false, 20, List.of()));
        when(router.chatWithFailover(any())).thenReturn(new ProviderChatResponse(
                "{\"fullySupported\":true,\"unsupportedClaims\":[]}",
                "qwen-plus", "test", 1, 1, 2));
        RagState state = RagState.initial(new AgenticRagRequest("怎么处理", null, null, "qwen-plus"));
        state.addRound(new RetrievalResult(List.of(hit(1), hit(2)), 3));
        state.setLatestGrade(new EvidenceGrade(true, List.of(1L), List.of("步骤"),
                List.of(), List.of(), NextAction.ANSWER, "充分"));

        var result = new GroundedAnswerGenerator(router, new ObjectMapper(), "qwen-plus").generateAndVerify(state);

        assertThat(result.answer()).isEqualTo("应按照运行手册执行 [E1]。");
        assertThat(result.citations()).extracting(citation -> citation.chunkId()).containsExactly(1L);
        assertThat(result.totalTokens()).isEqualTo(15);
        assertThat(result.sufficient()).isTrue();
    }

    private static RagHit hit(long id) {
        return new RagHit(new KnowledgeChunk(id, 10L, "src-" + id, "title-" + id,
                "evidence " + id, 10, null), .8, .2, .8);
    }
}

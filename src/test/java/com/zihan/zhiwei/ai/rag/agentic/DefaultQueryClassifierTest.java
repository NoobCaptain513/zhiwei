package com.zihan.zhiwei.ai.rag.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultQueryClassifierTest {

    @Test
    void shouldParseStructuredClassificationFromModel() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        when(router.chatWithFailover(any())).thenReturn(new ProviderChatResponse(
                "{\"needRag\":true,\"questionType\":\"TROUBLESHOOTING\","
                        + "\"needFreshness\":false,\"multiHop\":true,\"confidence\":0.93,"
                        + "\"reason\":\"需要内部证据\"}",
                "qwen-plus", "test", 1, 1, 2));
        DefaultQueryClassifier classifier = new DefaultQueryClassifier(router, new ObjectMapper(), "qwen-plus");

        var result = classifier.classify(new AgenticRagRequest("Redis 为什么一直 MOVED", null, null, "qwen-plus"));

        assertThat(result.needRag()).isTrue();
        assertThat(result.questionType()).isEqualTo(QuestionType.TROUBLESHOOTING);
        assertThat(result.multiHop()).isTrue();
    }
}

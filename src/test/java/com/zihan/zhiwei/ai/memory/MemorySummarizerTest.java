package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.pojo.entity.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySummarizerTest {

    @Test
    void parsesStrictStructuredModelOutput() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        when(router.executeWithFailover(any())).thenReturn(result("""
                {"summary":"Redis issue resolved","openLoops":["monitor"],
                 "decisions":["restart one node"],"entities":["redis-01"]}
                """));
        MemorySummarizer summarizer = new MemorySummarizer(router, new ObjectMapper());

        MemorySummarizer.Result result = summarizer.summarize("old", List.of(message(1L, "user", "help")));

        assertThat(result.summary()).isEqualTo("Redis issue resolved");
        assertThat(result.openLoops()).containsExactly("monitor");
        assertThat(result.decisions()).containsExactly("restart one node");
        assertThat(result.entities()).containsExactly("redis-01");
    }

    @Test
    void rejectsInvalidJsonInsteadOfProducingAnEmptyReplacement() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        when(router.executeWithFailover(any())).thenReturn(result("not-json"));
        MemorySummarizer summarizer = new MemorySummarizer(router, new ObjectMapper());

        assertThatThrownBy(() -> summarizer.summarize("old", List.of(message(1L, "user", "help"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structured");
    }

    @Test
    void rejectsJsonWithBlankSummaryOrUnknownFields() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        when(router.executeWithFailover(any())).thenReturn(result("{\"summary\":\"\",\"surprise\":true}"));
        MemorySummarizer summarizer = new MemorySummarizer(router, new ObjectMapper());

        assertThatThrownBy(() -> summarizer.summarize(null, List.of(message(1L, "user", "help"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structured");
    }

    private static FailoverResult result(String content) {
        return new FailoverResult(new ProviderChatResponse(content, "m", "p", 1, 1, 2),
                "p", "p", false, 1, List.of());
    }

    private static Message message(long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}

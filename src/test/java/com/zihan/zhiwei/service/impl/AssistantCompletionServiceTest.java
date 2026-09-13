package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedEvent;
import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedPublisher;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("流式完成事务服务")
class AssistantCompletionServiceTest {

    @Test
    @DisplayName("跨 Bean 的公开方法声明事务边界")
    void saveCompletionHasEffectiveTransactionBoundary() throws Exception {
        var method = AssistantCompletionService.class.getMethod(
                "saveCompletion", Long.class, String.class, ProviderChatResponse.class,
                String.class, long.class, boolean.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    @DisplayName("保存消息后记录用量并返回真实消息 ID")
    void savesMessageAndUsageTogether() {
        ConversationService conversations = mock(ConversationService.class);
        UsageRecorder usage = mock(UsageRecorder.class);
        AssistantCompletionService service = new AssistantCompletionService(conversations, usage);
        Message saved = new Message();
        saved.setId(42L);
        when(conversations.saveMessage(1L, "assistant", "done")).thenReturn(saved);
        ProviderChatResponse response = new ProviderChatResponse("done", "m", "p", 2, 3, 5);

        Message result = service.saveCompletion(1L, "done", response, "chat", 12L, true);

        assertThat(result.getId()).isEqualTo(42L);
        verify(usage).record(1L, 42L, response, "chat", 12L, true);
    }

    @Test
    @DisplayName("权威流式完成路径只登记一次事务后事件")
    void registersOnePostCommitEventForCompletedTurn() {
        ConversationService conversations = mock(ConversationService.class);
        UsageRecorder usage = mock(UsageRecorder.class);
        ConversationTurnCompletedPublisher publisher = mock(ConversationTurnCompletedPublisher.class);
        AssistantCompletionService service = new AssistantCompletionService(conversations, usage);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "turnCompletedPublisher", publisher);
        Message saved = new Message();
        saved.setId(42L);
        when(conversations.saveMessage(1L, "assistant", "done")).thenReturn(saved);
        ProviderChatResponse response = new ProviderChatResponse("done", "m", "p", 2, 3, 5);

        service.saveCompletion("u1", 1L, 41L, "done", response, "chat", 12L, true);

        verify(publisher).publishAfterCommit(new ConversationTurnCompletedEvent("u1", 1L, 41L, 42L));
    }

    @Test
    @DisplayName("用量写入失败向外抛出以触发事务回滚")
    void propagatesUsageFailure() {
        ConversationService conversations = mock(ConversationService.class);
        UsageRecorder usage = mock(UsageRecorder.class);
        AssistantCompletionService service = new AssistantCompletionService(conversations, usage);
        Message saved = new Message();
        saved.setId(42L);
        when(conversations.saveMessage(anyLong(), anyString(), anyString())).thenReturn(saved);
        doThrow(new IllegalStateException("usage failed")).when(usage)
                .record(anyLong(), anyLong(), any(), anyString(), anyLong(), anyBoolean());

        assertThatThrownBy(() -> service.saveCompletion(
                1L, "done", new ProviderChatResponse("done", "m", "p", 1, 1, 2),
                "agent", 3L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("usage failed");
    }
}

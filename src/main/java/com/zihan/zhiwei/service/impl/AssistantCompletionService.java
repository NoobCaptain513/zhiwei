package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedEvent;
import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedPublisher;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立 Spring Bean 上持有流式完成后的短事务，避免同类私有方法自调用绕过事务代理。
 */
@Service
@RequiredArgsConstructor
public class AssistantCompletionService {

    private final ConversationService conversationService;
    private final UsageRecorder usageRecorder;

    @Autowired(required = false)
    private ConversationTurnCompletedPublisher turnCompletedPublisher;

    @Transactional
    public Message saveCompletion(Long conversationId, String content,
                                  ProviderChatResponse providerResponse,
                                  String mode, long latencyMs, boolean degraded) {
        Message assistantMessage = conversationService.saveMessage(
                conversationId, "assistant", content);
        usageRecorder.record(conversationId, assistantMessage.getId(),
                providerResponse, mode, latencyMs, degraded);
        return assistantMessage;
    }

    /** Authoritative streaming completion path: message, usage and event registration share one transaction. */
    @Transactional
    public Message saveCompletion(String userId, Long conversationId, long userMessageId, String content,
                                  ProviderChatResponse providerResponse,
                                  String mode, long latencyMs, boolean degraded) {
        Message assistantMessage = saveCompletion(
                conversationId, content, providerResponse, mode, latencyMs, degraded);
        publish(userId, conversationId, userMessageId, assistantMessage);
        return assistantMessage;
    }

    /** Completion path for system-generated replies that have no provider usage. */
    @Transactional
    public Message saveCompletion(String userId, Long conversationId, long userMessageId, String content) {
        Message assistantMessage = conversationService.saveMessage(conversationId, "assistant", content);
        publish(userId, conversationId, userMessageId, assistantMessage);
        return assistantMessage;
    }

    private void publish(String userId, Long conversationId, long userMessageId, Message assistantMessage) {
        if (turnCompletedPublisher != null) {
            turnCompletedPublisher.publishAfterCommit(new ConversationTurnCompletedEvent(
                    userId, conversationId, userMessageId, assistantMessage.getId()));
        }
    }
}

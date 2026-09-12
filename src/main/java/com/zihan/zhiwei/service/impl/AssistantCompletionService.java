package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import lombok.RequiredArgsConstructor;
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
}

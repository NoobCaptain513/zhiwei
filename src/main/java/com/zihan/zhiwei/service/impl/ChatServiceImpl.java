package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.RagMessageAugmentor;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.dto.ChatRequest;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ChatService;
import com.zihan.zhiwei.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationService conversationService;
    private final ModelProviderRouter modelProviderRouter;
    private final UsageRecorder usageRecorder;
    private final RagMessageAugmentor ragMessageAugmentor;

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        List<Message> history = conversationService.listMessages(conversation.getId());
        List<ProviderChatMessage> providerMessages = new ArrayList<>();
        for (Message item : history) {
            providerMessages.add(new ProviderChatMessage(item.getRole(), item.getContent()));
        }
        providerMessages = ragMessageAugmentor.augmentIfEnabled(providerMessages);

        FailoverResult failoverResult = modelProviderRouter.executeWithFailover(
                new ProviderChatRequest(request.model(), providerMessages));
        var providerResponse = failoverResult.response();

        Message assistantMessage = conversationService.saveMessage(
                conversation.getId(), "assistant", providerResponse.content());

        usageRecorder.record(
                conversation.getId(),
                assistantMessage.getId(),
                providerResponse,
                UsageRecorder.MODE_CHAT,
                failoverResult.latencyMs(),
                failoverResult.degraded());

        return new ChatResponse(
                conversation.getId(),
                assistantMessage.getId(),
                providerResponse.content(),
                providerResponse.model(),
                providerResponse.provider(),
                providerResponse.totalTokens()
        );
    }

    /**
     * D15: 流式聊天。
     * 会话管理 + RAG + 流式路由；完整文本由 onToken 收集后入库。
     */
    @Override
    public StreamResult streamChat(ChatRequest request, Consumer<String> onToken) {
        // 1. 会话管理
        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        // 2. 加载历史 + RAG 增强
        List<Message> history = conversationService.listMessages(conversation.getId());
        List<ProviderChatMessage> providerMessages = new ArrayList<>();
        for (Message item : history) {
            providerMessages.add(new ProviderChatMessage(item.getRole(), item.getContent()));
        }
        providerMessages = ragMessageAugmentor.augmentIfEnabled(providerMessages);

        // 3. 收集完整文本（流式 + 入库）
        StringBuilder fullContent = new StringBuilder();
        Consumer<String> wrappedOnToken = token -> {
            fullContent.append(token);
            onToken.accept(token);
        };

        // 4. 路由 + 流式调用
        ProviderChatRequest providerRequest = new ProviderChatRequest(request.model(), providerMessages);
        StreamResult streamResult = modelProviderRouter.streamChatWithFailover(providerRequest, wrappedOnToken);

        // 5. 保存助手消息
        String content = fullContent.toString();
        Message assistantMessage = conversationService.saveMessage(
                conversation.getId(), "assistant", content);

        // 6. 记录 usage
        ProviderChatResponse providerResponse = new ProviderChatResponse(
                content, streamResult.model(), streamResult.provider(),
                streamResult.promptTokens(), streamResult.completionTokens(), streamResult.totalTokens());
        usageRecorder.record(conversation.getId(), assistantMessage.getId(),
                providerResponse, UsageRecorder.MODE_CHAT, 0L, false);

        return streamResult;
    }
}
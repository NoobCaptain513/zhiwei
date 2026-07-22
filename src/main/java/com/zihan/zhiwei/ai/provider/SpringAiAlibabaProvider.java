package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI Alibaba DashScope 的 Provider 实现。
 */
@Component
@RequiredArgsConstructor
public class SpringAiAlibabaProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "spring-ai-alibaba";

    private final ChatModel chatModel;

    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String defaultModel;

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        List<Message> messages = buildSpringMessages(request);
        String model = request.model() != null ? request.model() : defaultModel;
        ChatResponse response = chatModel.call(new Prompt(messages));

        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new BusinessException("AI 返回内容为空");
        }

        var usage = response.getMetadata().getUsage();
        int promptTokens = usage != null ? (int) usage.getPromptTokens() : 0;
        int completionTokens = usage != null ? (int) usage.getCompletionTokens() : 0;
        int totalTokens = usage != null ? (int) usage.getTotalTokens() : promptTokens + completionTokens;

        return new ProviderChatResponse(content, model, PROVIDER_NAME,
                promptTokens, completionTokens, totalTokens);
    }

    /**
     * D15: Spring AI 当前不支持流式回调，回退到同步 chat() 再一次性 emit。
     * 后续可替换为 StreamingChatModel。
     */
    @Override
    public StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        ProviderChatResponse response = chat(request);
        if (response.content() != null && !response.content().isEmpty()) {
            onToken.accept(response.content());
        }
        return StreamResult.of(response.model(), response.provider(),
                response.promptTokens(), response.completionTokens());
    }

    private List<Message> buildSpringMessages(ProviderChatRequest request) {
        List<Message> messages = new ArrayList<>();
        for (ProviderChatMessage item : request.messages()) {
            messages.add(toSpringMessage(item));
        }
        return messages;
    }

    private Message toSpringMessage(ProviderChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            case "user" -> new UserMessage(message.content());
            default -> throw new BusinessException("不支持的消息角色: " + message.role());
        };
    }
}
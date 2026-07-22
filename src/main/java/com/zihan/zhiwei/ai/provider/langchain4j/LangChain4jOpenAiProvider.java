package com.zihan.zhiwei.ai.provider.langchain4j;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * D5+D15: LangChain4j Claude-compatible Provider。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.langchain4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangChain4jOpenAiProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "langchain4j-openai";

    private final ChatLanguageModel chatLanguageModel;

    @Value("${zhiwei.ai.langchain4j.model:qwen-plus}")
    private String defaultModel;

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        List<ChatMessage> messages = buildLcMessages(request);
        Response<AiMessage> response = chatLanguageModel.generate(messages);

        if (response == null || response.content() == null || response.content().text() == null
                || response.content().text().isBlank()) {
            throw new BusinessException("LangChain4j 返回内容为空");
        }

        String model = request.model() != null ? request.model() : defaultModel;
        TokenUsage usage = response.tokenUsage();
        int promptTokens = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int completionTokens = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        int totalTokens = usage != null && usage.totalTokenCount() != null
                ? usage.totalTokenCount() : promptTokens + completionTokens;

        return new ProviderChatResponse(response.content().text(),
                model, PROVIDER_NAME, promptTokens, completionTokens, totalTokens);
    }

    /**
     * D15: LangChain4j ChatLanguageModel 当前不支持流式回调，回退同步。
     * 后续可替换为 StreamingChatLanguageModel。
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

    private List<ChatMessage> buildLcMessages(ProviderChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ProviderChatMessage item : request.messages()) {
            messages.add(toLcMessage(item));
        }
        return messages;
    }

    private ChatMessage toLcMessage(ProviderChatMessage message) {
        return switch (message.role()) {
            case "system" -> SystemMessage.from(message.content());
            case "assistant" -> AiMessage.from(message.content());
            case "user" -> UserMessage.from(message.content());
            default -> throw new BusinessException("不支持的消息角色: " + message.role());
        };
    }
}
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
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * D5+D15: LangChain4j Claude-compatible Provider。
 * 修改：使用 StreamingChatLanguageModel 实现真流式输出。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.langchain4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangChain4jOpenAiProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "langchain4j-openai";

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel; // 新增

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
     * 真流式实现：使用 LangChain4j 的 StreamingChatLanguageModel。
     */
    @Override
    public StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        List<ChatMessage> messages = buildLcMessages(request);
        String model = request.model() != null ? request.model() : defaultModel;

        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);
        CompletableFuture<Response<AiMessage>> future = new CompletableFuture<>();

        try {
            streamingChatLanguageModel.generate(messages, new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    // 实时回调每个 token
                    if (token != null && !token.isEmpty()) {
                        onToken.accept(token);
                    }
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    // 流式完成后获取完整响应
                    future.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            // 阻塞等待流式完成
            Response<AiMessage> response = future.join();

            // 提取 token 统计
            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                promptTokens.set(usage.inputTokenCount() != null ? usage.inputTokenCount() : 0);
                completionTokens.set(usage.outputTokenCount() != null ? usage.outputTokenCount() : 0);
            }

            return StreamResult.of(model, PROVIDER_NAME, promptTokens.get(), completionTokens.get());

        } catch (Exception e) {
            throw new BusinessException("LangChain4j Stream 调用失败: " + e.getMessage());
        }
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
package com.zihan.zhiwei.ai.provider;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于阿里云官方 DashScope SDK 的 Provider 实现。
 * 修改：使用官方 SDK 的 Generation.streamCall() 实现真流式输出。
 */
@Slf4j
@Component
public class SpringAiAlibabaProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "spring-ai-alibaba";

    private final ChatModel chatModel;
    private final Generation generation;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String defaultModel;

    public SpringAiAlibabaProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.generation = new Generation();
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = buildSpringMessages(request);
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
     * 真流式实现：使用阿里云官方 DashScope SDK 的 streamCall() 方法。
     */
    @Override
    public StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        String model = request.model() != null ? request.model() : defaultModel;
        List<Message> messages = buildDashScopeMessages(request);

        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);

        try {
            // 构建流式请求参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .incrementalOutput(true)  // 开启增量输出（真流式关键）
                    .build();

            // 调用流式接口
            Flowable<GenerationResult> stream = generation.streamCall(param);

            // 阻塞消费流式响应
            stream.blockingForEach(result -> {
                // 提取增量文本
                String content = result.getOutput().getChoices().get(0).getMessage().getContent();
                if (content != null && !content.isEmpty()) {
                    onToken.accept(content);
                }

                // 提取 token 统计（通常在最后一个 chunk 返回）
                var usage = result.getUsage();
                if (usage != null) {
                    promptTokens.set(usage.getInputTokens());
                    completionTokens.set(usage.getOutputTokens());
                }
            });

            log.debug("[{}Stream] done model={} promptTokens={} completionTokens={}",
                    PROVIDER_NAME, model, promptTokens.get(), completionTokens.get());

            return StreamResult.of(model, PROVIDER_NAME, promptTokens.get(), completionTokens.get());

        } catch (NoApiKeyException | InputRequiredException e) {
            throw new BusinessException("DashScope SDK 参数错误: " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("DashScope Stream 调用失败: " + e.getMessage());
        }
    }

    /**
     * 构建 Spring AI 消息列表（用于同步调用）
     */
    private List<org.springframework.ai.chat.messages.Message> buildSpringMessages(ProviderChatRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (ProviderChatMessage item : request.messages()) {
            messages.add(toSpringMessage(item));
        }
        return messages;
    }

    private org.springframework.ai.chat.messages.Message toSpringMessage(ProviderChatMessage message) {
        return switch (message.role()) {
            case "system" -> new org.springframework.ai.chat.messages.SystemMessage(message.content());
            case "assistant" -> new org.springframework.ai.chat.messages.AssistantMessage(message.content());
            case "user" -> new org.springframework.ai.chat.messages.UserMessage(message.content());
            default -> throw new BusinessException("不支持的消息角色: " + message.role());
        };
    }

    /**
     * 构建 DashScope SDK 消息列表（用于流式调用）
     */
    private List<Message> buildDashScopeMessages(ProviderChatRequest request) {
        List<Message> messages = new ArrayList<>();
        for (ProviderChatMessage item : request.messages()) {
            messages.add(toDashScopeMessage(item));
        }
        return messages;
    }

    private Message toDashScopeMessage(ProviderChatMessage message) {
        Role role = switch (message.role()) {
            case "system" -> Role.SYSTEM;
            case "assistant" -> Role.ASSISTANT;
            case "user" -> Role.USER;
            default -> throw new BusinessException("不支持的消息角色: " + message.role());
        };

        return Message.builder()
                .role(role.getValue())
                .content(message.content())
                .build();
    }
}
package com.zihan.zhiwei.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * D5: LangChain4j Claude-compatible ChatLanguageModel 配置。
 * <p>
 * 默认对接 DashScope Claude 兼容端点；也可改 base-url / api-key 指向任意兼容服务。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "zhiwei.ai.langchain4j",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LangChain4jConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${zhiwei.ai.langchain4j.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${zhiwei.ai.langchain4j.api-key:${spring.ai.dashscope.api-key:}}") String apiKey,
            @Value("${zhiwei.ai.langchain4j.model:qwen-plus}") String model,
            @Value("${zhiwei.ai.langchain4j.timeout-seconds:60}") long timeoutSeconds,
            @Value("${zhiwei.ai.langchain4j.temperature:0.7}") Double temperature,
            @Value("${zhiwei.ai.langchain4j.max-tokens:2048}") Integer maxTokens) {

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
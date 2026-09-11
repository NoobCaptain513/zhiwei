package com.zihan.zhiwei.ai.provider.dto;

/**
 * Provider 层聊天响应。
 */
public record ProviderChatResponse(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        int totalTokens
) {}
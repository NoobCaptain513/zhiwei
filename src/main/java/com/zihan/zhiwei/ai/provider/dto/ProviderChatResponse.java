package com.zihan.zhiwei.ai.provider.dto;

import java.util.List;

/**
 * Provider 层聊天响应。
 */
public record ProviderChatResponse(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        List<ToolCall> toolCalls
) {
    public ProviderChatResponse(String content, String model, String provider,
                                int promptTokens, int completionTokens, int totalTokens) {
        this(content, model, provider, promptTokens, completionTokens, totalTokens, List.of());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}

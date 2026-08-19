package com.zihan.zhiwei.ai.provider.dto;

import java.util.List;

/**
 * Provider 层聊天请求。
 */
public record ProviderChatRequest(
        String model,
        List<ProviderChatMessage> messages,
        List<ToolDefinition> tools,
        String toolChoice
) {
    public ProviderChatRequest(String model, List<ProviderChatMessage> messages) {
        this(model, messages, List.of(), null);
    }
}

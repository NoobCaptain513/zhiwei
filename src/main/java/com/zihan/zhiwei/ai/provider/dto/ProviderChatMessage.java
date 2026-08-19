package com.zihan.zhiwei.ai.provider.dto;

import java.util.List;

/**
 * Provider 层统一消息格式。
 */
public record ProviderChatMessage(
    String role,
    String content,
    String toolCallId,
    String toolName,
    List<ToolCall> toolCalls
) {
    public ProviderChatMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    public static ProviderChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ProviderChatMessage("assistant", null, null, null, toolCalls);
    }

    public static ProviderChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new ProviderChatMessage("assistant", content, null, null, toolCalls);
    }

    public static ProviderChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new ProviderChatMessage("tool", content, toolCallId, toolName, List.of());
    }
}

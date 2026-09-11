package com.zihan.zhiwei.ai.provider.dto;

/**
 * Provider 层统一消息格式。
 */
public record ProviderChatMessage(
    String role,
    String content
) {}

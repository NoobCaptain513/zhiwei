package com.zihan.zhiwei.pojo.dto;

/**
 * 聊天响应 DTO。
 */
public record ChatResponse(
        Long conversationId,
        Long messageId,
        String content,
        String model,
        String provider,
        int totalTokens
) {}
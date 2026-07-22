package com.zihan.zhiwei.pojo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求 DTO。
 * 对应接口：POST /api/ai/chat
 */
public record ChatRequest(

        /** 用户ID，第一周可先传固定值 */
        @NotBlank(message = "userId 不能为空")
        String userId,

        /** 会话ID，为空则自动创建新会话 */
        Long conversationId,

        /** 用户输入内容 */
        @NotBlank(message = "message 不能为空")
        String message,

        /** 指定模型，可选 */
        String model
) {}
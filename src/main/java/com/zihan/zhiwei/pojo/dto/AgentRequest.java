package com.zihan.zhiwei.pojo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * D14: Agent 请求 DTO。
 * POST /api/ai/agent
 */
public record AgentRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,

        Long conversationId,

        @NotBlank(message = "message 不能为空")
        String message,

        /** 指定模型，可选 */
        String model,

        /** 是否跳过工具调用，纯聊天 */
        boolean chatOnly
) {}
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
        boolean chatOnly,

        /** 指定 Provider，可选（如 "ollama", "native-dashscope"），用于 RAG Embedding 选择 */
        String preferredProvider,

        /** 幂等键（可选）：客户端重试时携带同一 UUID，服务端返回首次处理结果，避免重复扣费 */
        String idempotencyKey
) {}
package com.zihan.zhiwei.pojo.dto;

import com.zihan.zhiwei.ai.reply.AgentReply;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D14: Agent 响应 DTO。
 * POST /api/ai/agent
 */
@Data
@Builder
public class AgentResponse {

    private Long conversationId;
    private Long messageId;

    /** 大模型自然语言回复 */
    private String content;

    /** 结构化卡片（服务器/工单/指标/知识） */
    private List<AgentReply.Card> cards;

    /** 识别到的意图 */
    private String intent;

    /** 使用的 Provider */
    private String provider;

    /** 使用的模型 */
    private String model;

    /** 总 token 数 */
    private int totalTokens;

    /** 是否发生了降级 */
    private boolean degraded;
}
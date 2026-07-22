package com.zihan.zhiwei.ai.stream;

import com.zihan.zhiwei.ai.reply.AgentReply;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D15: Agent 流式调用完成后的元数据。
 */
@Data
@Builder
public class AgentStreamResult {

    private Long conversationId;
    private Long messageId;
    private String content;
    private List<AgentReply.Card> cards;
    private String intent;
    private String model;
    private String provider;
    private int totalTokens;
    private boolean degraded;
}
package com.zihan.zhiwei.service;

import com.zihan.zhiwei.ai.stream.AgentStreamResult;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;

import java.util.function.Consumer;

public interface AgentService {

    /** Agent 全链路（同步） */
    AgentResponse agent(AgentRequest request);

    /**
     * D15: Agent 全链路（流式）。
     * onToken —— 每个 token 的回调
     * onCard  —— 卡片 JSON 回调（流结束后触发）
     */
    AgentStreamResult streamAgent(AgentRequest request,
                                  Consumer<String> onToken,
                                  Consumer<String> onCard);
}
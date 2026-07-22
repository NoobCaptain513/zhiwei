package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * D13: Agent 回复组装的统一入口。
 * 负责把模型文本 + 工具卡片 + 兜底卡片 编码成 AgentReply。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentReplyService {

    private final ResultCardAssembler resultCardAssembler;
    private final ToolResultCollector toolResultCollector;

    /**
     * 标准组装（有工具调用时）
     */
    public AgentReply buildReply(String modelText, String intent, boolean degraded) {
        List<ToolCallResult> toolResults = toolResultCollector.getAll();
        List<AgentReply.Card> cards = resultCardAssembler.assemble(toolResults);

        return AgentReply.builder()
                .text(modelText)
                .cards(cards)
                .toolResults(toolResults)
                .intent(intent)
                .degraded(degraded)
                .build();
    }

    /**
     * 兜底组装（无工具调用时）
     */
    public AgentReply buildFallbackReply(String modelText, String intent, List<AgentReply.Card> extraCards) {
        List<AgentReply.Card> existing = resultCardAssembler.assemble(toolResultCollector.getAll());
        List<AgentReply.Card> merged = resultCardAssembler.merge(existing, extraCards);

        return AgentReply.builder()
                .text(modelText)
                .cards(merged)
                .toolResults(toolResultCollector.getAll())
                .intent(intent)
                .build();
    }

    /**
     * 把 AgentReply 编码为可存入 message.content 的字符串
     */
    public String encode(AgentReply reply) {
        return reply.encode();
    }

    /**
     * 从 message.content 还原 AgentReply
     */
    public AgentReply decode(String raw) {
        return AgentReply.decode(raw);
    }
}
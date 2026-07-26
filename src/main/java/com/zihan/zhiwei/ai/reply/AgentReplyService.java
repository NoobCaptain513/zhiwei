package com.zihan.zhiwei.ai.reply;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
     * P1-5 修复：注入 ObjectMapper 用于 JSON 序列化，
     * 替代 AgentReply 中脆弱的分隔符拼接方式。
     */
    private final ObjectMapper objectMapper;

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
     * P1-5 修复：使用 Jackson JSON 序列化替代分隔符拼接，避免用户内容含特殊字符时损坏数据。
     */
    public String encode(AgentReply reply) {
        try {
            return objectMapper.writeValueAsString(reply);
        } catch (JsonProcessingException e) {
            log.error("[AgentReply] encode failed, fallback to legacy", e);
            // 回退到旧分隔符方式
            return reply.encode();
        }
    }

    /**
     * P1-5 修复：先用 Jackson JSON 反序列化，失败则回退旧分隔符解码（兼容历史数据）。
     */
    public AgentReply decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentReply.builder().text(raw).cards(List.of()).build();
        }
        // 先尝试 JSON 解码
        try {
            return objectMapper.readValue(raw, AgentReply.class);
        } catch (JsonProcessingException e) {
            log.debug("[AgentReply] JSON decode failed, trying legacy format");
            // 兼容旧格式：回退到分隔符解码
            return AgentReply.decode(raw);
        }
    }
}
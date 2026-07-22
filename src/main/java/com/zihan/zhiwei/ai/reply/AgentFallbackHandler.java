package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D13: Agent 兜底逻辑。
 * 当模型回复里没有调用任何工具时，走这条链路：
 *   1. 原句去 RAG 检索
 *   2. 用 sourceId 回查
 *   3. 拼成卡片兜底返回
 *
 * 目的：即使模型「偷懒」不调工具，用户也能看到知识库里的结构化信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentFallbackHandler {

    private final AiRagService aiRagService;
    private final RagCardBuilder ragCardBuilder;
    private final ResultCardAssembler resultCardAssembler;
    private final ToolResultCollector toolResultCollector;

    /**
     * 判断是否需要兜底 + 执行兜底。
     * @param userMessage  用户原始输入
     * @param modelText    模型回复文本
     * @param intent       识别到的意图
     * @return 兜底后的 AgentReply（若不需要兜底则返回 null，表示模型回复足够）
     */
    public AgentReply fallbackIfNeeded(String userMessage, String modelText, String intent) {
        List<ToolCallResult> toolResults = toolResultCollector.getAll();

        // 有工具调用结果 → 不需要兜底，卡片由 ResultCardAssembler 组装
        if (!toolResults.isEmpty()) {
            log.debug("[Fallback] skip: toolResults not empty (size={})", toolResults.size());
            return null;
        }

        // 模型回复很短且没有具体信息 → 尝试 RAG 兜底
        boolean shortReply = modelText == null || modelText.length() < 30;
        boolean noStructured = modelText == null || !containsStructuredHint(modelText);

        if (!shortReply && !noStructured) {
            log.debug("[Fallback] skip: model reply is rich enough");
            return null;
        }

        log.info("[Fallback] triggered: user='{}' intent={} textLen={}", userMessage, intent, modelText == null ? 0 : modelText.length());

        // 1. 原句检索
        List<RagHit> hits = aiRagService.search(userMessage, 5, 20);

        // 2. 转成卡片
        List<AgentReply.Card> ragCards = ragCardBuilder.buildCards(hits);

        // 3. 与工具卡片合并（此时工具卡片为空）
        List<AgentReply.Card> allCards = resultCardAssembler.merge(List.of(), ragCards);

        // 4. 构建兜底回复
        String fallbackText = modelText != null ? modelText : "抱歉，我没能直接回答这个问题。以下是知识库中的相关内容：";
        if (!ragCards.isEmpty()) {
            fallbackText += "\n\n（已从知识库检索到 " + ragCards.size() + " 条相关结果，请参考下方卡片）";
        }

        return AgentReply.builder()
                .text(fallbackText)
                .cards(allCards)
                .toolResults(List.of())
                .intent(intent)
                .build();
    }

    /** 粗判模型回复是否包含结构化信息（工单号 / 服务器名 / 指标值等） */
    private boolean containsStructuredHint(String text) {
        return text.contains("TK-")
                || text.contains("CPU")
                || text.contains("内存")
                || text.contains("QPS")
                || text.contains("v2.")
                || text.contains("部署")
                || text.contains("工单");
    }
}
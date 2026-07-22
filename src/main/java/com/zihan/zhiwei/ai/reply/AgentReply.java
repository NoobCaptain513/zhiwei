package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.tool.ToolCallResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D13: Agent 回复的统一编码格式。
 * 大模型的文本输出 + 结构化卡片（工具结果 / RAG 结果）合在一起。
 */
@Data
@Builder
public class AgentReply {

    /** 大模型生成的自然语言回复 */
    private String text;

    /** 结构化卡片列表（服务器状态 / 工单 / 指标等） */
    private List<Card> cards;

    /** 本次调用用到的工具结果（调试 / 审计） */
    private List<ToolCallResult> toolResults;

    /** 主要意图 */
    private String intent;

    /** 是否发生了降级 */
    private boolean degraded;

    /** 序列化为 JSON 字符串（存入 message.content） */
    public String encode() {
        // 先用简单分隔符编码，后续可换 JSON
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.isBlank()) {
            sb.append(text);
        }
        if (cards != null && !cards.isEmpty()) {
            sb.append("\n<!--CARDS:");
            for (int i = 0; i < cards.size(); i++) {
                if (i > 0) sb.append("|||");
                sb.append(cards.get(i).encode());
            }
            sb.append(":CARDS-->");
        }
        return sb.toString();
    }

    /** 从 message.content 解码（读历史时还原卡片） */
    public static AgentReply decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentReply.builder().text(raw).cards(List.of()).build();
        }
        int start = raw.indexOf("<!--CARDS:");
        if (start < 0) {
            return AgentReply.builder().text(raw).cards(List.of()).build();
        }
        String textPart = raw.substring(0, start).trim();
        int end = raw.indexOf(":CARDS-->", start);
        if (end < 0) {
            return AgentReply.builder().text(textPart).cards(List.of()).build();
        }
        String cardsRaw = raw.substring(start + 10, end);
        String[] parts = cardsRaw.split("\\|\\|\\|", -1);
        List<Card> cards = new java.util.ArrayList<>();
        for (String p : parts) {
            Card card = Card.decode(p.trim());
            if (card != null) cards.add(card);
        }
        return AgentReply.builder().text(textPart).cards(cards).build();
    }

    /**
     * D13: 结构化卡片数据。
     */
    @Data
    @Builder
    public static class Card {
        /** 卡片类型：server / ticket / metric / rag */
        private String type;
        /** 卡片标题 */
        private String title;
        /** 卡片内容行（key-value） */
        private java.util.Map<String, String> fields;
        /** 来源 ID（用于去重） */
        private String sourceId;

        /** 编码为单行字符串 */
        public String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(type).append("||");
            sb.append(title).append("||");
            sb.append(sourceId == null ? "" : sourceId).append("||");
            if (fields != null) {
                for (java.util.Map.Entry<String, String> e : fields.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append(";;");
                }
            }
            return sb.toString();
        }

        /** 从单行字符串解码 */
        public static Card decode(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|\\|", -1);
            if (parts.length < 3) return null;
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
            if (parts.length > 3 && !parts[3].isBlank()) {
                for (String kv : parts[3].split(";;")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        fields.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }
            return Card.builder()
                    .type(parts[0])
                    .title(parts[1])
                    .sourceId(parts[2])
                    .fields(fields)
                    .build();
        }
    }
}
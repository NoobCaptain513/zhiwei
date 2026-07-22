package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D13: 把 RAG 检索结果也组装成 Card，供 AgentReply 合并。
 * 用于「模型未调工具 → 原句搜索 → 卡片兜底」链路。
 */
@Component
@RequiredArgsConstructor
public class RagCardBuilder {

    /**
     * RAG hits → Card 列表
     */
    public List<AgentReply.Card> buildCards(List<RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<AgentReply.Card> cards = new ArrayList<>();
        for (RagHit hit : hits) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("内容", hit.chunk().content());
            fields.put("向量分", String.format("%.4f", hit.vectorScore()));
            fields.put("字面分", String.format("%.4f", hit.lexicalScore()));
            fields.put("综合分", String.format("%.4f", hit.finalScore()));
            if (hit.chunk().documentId() != null) {
                fields.put("文档ID", String.valueOf(hit.chunk().documentId()));
            }
            String sourceId = hit.chunk().sourceId() != null ? hit.chunk().sourceId() : "rag:" + hit.chunk().id();
            cards.add(AgentReply.Card.builder()
                    .type("rag")
                    .title(hit.chunk().title() != null ? hit.chunk().title() : "知识片段")
                    .sourceId(sourceId)
                    .fields(fields)
                    .build());
        }
        return cards;
    }
}
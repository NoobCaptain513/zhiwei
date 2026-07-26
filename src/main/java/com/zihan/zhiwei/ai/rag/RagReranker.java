package com.zihan.zhiwei.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D30: LLM 精排（可选）。
 * 对 RRF 融合后的 top-N 候选用 LLM 进行语义相关性打分。
 * 失败时静默回退，保留 RRF 排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagReranker {

    private final ModelProviderRouter modelProviderRouter;
    private final ObjectMapper objectMapper;

    @Value("${zhiwei.ai.rag.rerank-enabled:false}")
    private boolean rerankEnabled;

    @Value("${zhiwei.ai.rag.rerank-top-n:10}")
    private int rerankTopN;

    @Value("${zhiwei.ai.rag.rerank-model:qwen-plus}")
    private String rerankModel;

    /**
     * 对候选列表精排，finalScore 覆写为 LLM 分值。
     * 仅精排前 rerankTopN 条，其余保持原序追加。
     */
    public List<RagHit> rerank(String query, List<RagHit> candidates) {
        if (!rerankEnabled || candidates.isEmpty()) {
            return candidates;
        }

        int n = Math.min(rerankTopN, candidates.size());
        List<RagHit> toRerank = new ArrayList<>(candidates.subList(0, n));
        List<RagHit> rest = candidates.size() > n
                ? new ArrayList<>(candidates.subList(n, candidates.size()))
                : List.of();

        try {
            String prompt = buildRerankPrompt(query, toRerank);
            ProviderChatRequest request = new ProviderChatRequest(rerankModel, List.of(
                    new ProviderChatMessage("system",
                            "你是相关性评分助手。对知识片段按与问题的相关性打分 1-5（5=高度相关）。"
                                    + "只返回 JSON 数组：{\"items\":[{\"id\":数字,\"score\":1-5}]}，不要其他文字。"),
                    new ProviderChatMessage("user", prompt)
            ));

            ProviderChatResponse response = modelProviderRouter.chatWithFailover(request);
            Map<Long, Integer> scores = parseRerankScores(response.content());

            List<RagHit> reranked = new ArrayList<>();
            for (RagHit h : toRerank) {
                Integer s = scores.get(h.chunk().id());
                double newScore = s != null ? s / 5.0 : 0.4;
                reranked.add(new RagHit(h.chunk(), h.vectorScore(), h.lexicalScore(), newScore));
            }
            reranked.sort(Comparator.comparingDouble(RagHit::finalScore).reversed());
            reranked.addAll(rest);

            log.info("[Rerank] query='{}' reranked={} scores={}", query, n, scores);
            return reranked;

        } catch (Exception e) {
            log.warn("[Rerank] failed, fallback to RRF: {}", e.getMessage());
            return candidates;
        }
    }

    private String buildRerankPrompt(String query, List<RagHit> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query).append("\n\n");
        sb.append("候选知识片段：\n");
        for (RagHit hit : candidates) {
            String title = hit.chunk().title() != null ? hit.chunk().title() : "";
            String preview = hit.chunk().content();
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "...";
            }
            sb.append("id=").append(hit.chunk().id())
                    .append(" title=").append(title)
                    .append(" | ").append(preview).append("\n");
        }
        sb.append("\n对每个片段打分 1-5，返回 JSON。");
        return sb.toString();
    }

    private Map<Long, Integer> parseRerankScores(String content) {
        Map<Long, Integer> scores = new HashMap<>();
        if (content == null || content.isBlank()) {
            return scores;
        }
        try {
            String json = extractJson(content);
            if (json == null) {
                return scores;
            }
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("items");
            if (items == null) {
                return scores;
            }
            for (Map<String, Object> item : items) {
                Long id = item.get("id") instanceof Number n ? n.longValue() : null;
                Integer score = item.get("score") instanceof Number n ? n.intValue() : null;
                if (id != null && score != null) {
                    scores.put(id, Math.max(1, Math.min(5, score)));
                }
            }
        } catch (Exception e) {
            log.debug("[Rerank] parse failed: {}", e.getMessage());
        }
        return scores;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}

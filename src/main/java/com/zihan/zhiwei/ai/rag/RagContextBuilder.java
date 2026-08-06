package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * D11+D31: RAG 上下文构建器。
 * D31: 支持传入对话历史用于查询改写。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagContextBuilder {

    private final AiRagService aiRagService;

    @Value("${zhiwei.ai.rag.top-k:5}")
    private int defaultTopK;

    @Value("${zhiwei.ai.rag.candidate-k:20}")
    private int defaultCandidateK;

    public List<RagHit> retrieve(String query) {
        return aiRagService.searchWithRewrite(query, null, null, defaultTopK, defaultCandidateK);
    }

    /**
     * D31: 带历史上下文的检索。
     */
    public List<RagHit> retrieve(String query, String historyContext) {
        return aiRagService.searchWithRewrite(query, historyContext, null, defaultTopK, defaultCandidateK);
    }

    /**
     * 带 Provider 参数的检索（根据 Provider 动态选择 Embedding）
     */
    public List<RagHit> retrieve(String query, String historyContext, String provider) {
        return aiRagService.searchWithRewrite(query, historyContext, provider, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> retrieve(String query, Integer topK, Integer candidateK) {
        return aiRagService.searchWithRewrite(query, null, null, topK, candidateK);
    }

    public String buildContextBlock(String query) {
        return buildContextBlock(retrieve(query));
    }

    /**
     * D31: 带对话历史的上下文构建。
     */
    public String buildContextBlock(String query, String historyContext) {
        return buildContextBlock(retrieve(query, historyContext));
    }

    /**
     * 带 Provider 参数的上下文构建（根据 Provider 动态选择 Embedding）
     */
    public String buildContextBlock(String query, String historyContext, String provider) {
        return buildContextBlock(retrieve(query, historyContext, provider));
    }

    public String buildContextBlock(List<RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        String body = hits.stream()
                .map(hit -> {
                    String title = hit.chunk().title() == null ? "" : hit.chunk().title();
                    String source = hit.chunk().sourceId() == null ? "" : hit.chunk().sourceId();
                    return "- [" + source + "] " + title + "\n  " + hit.chunk().content()
                            + "\n  (score=" + String.format("%.4f", hit.finalScore()) + ")";
                })
                .collect(Collectors.joining("\n"));
        return """
                你是企业运维助手。请优先依据下列知识库片段回答；若片段不足，再结合通用知识，并明确说明不确定之处。

                【知识库检索结果】
                %s
                """.formatted(body);
    }
}

package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.RagHit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * D11: 把检索结果拼成可注入 system prompt / Advisor 的上下文文本。
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
        return aiRagService.search(query, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> retrieve(String query, Integer topK, Integer candidateK) {
        return aiRagService.search(query, topK, candidateK);
    }

    /** Native / 通用：拼进 system 消息 */
    public String buildContextBlock(String query) {
        return buildContextBlock(retrieve(query));
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
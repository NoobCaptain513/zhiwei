package com.zihan.zhiwei.ai.provider.langchain4j;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D11: LangChain4j ContentRetriever —— 对接 AiRagService。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangChain4jRagContentRetriever implements ContentRetriever {

    private final AiRagService aiRagService;

    @Value("${zhiwei.ai.rag.top-k:5}")
    private int topK;

    @Value("${zhiwei.ai.rag.candidate-k:20}")
    private int candidateK;

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        List<RagHit> hits = aiRagService.search(query.text(), topK, candidateK);
        List<Content> contents = new ArrayList<>(hits.size());
        for (RagHit hit : hits) {
            String text = hit.chunk().content();
            if (hit.chunk().title() != null && !hit.chunk().title().isBlank()) {
                text = hit.chunk().title() + "\n" + text;
            }
            contents.add(Content.from(text));
        }
        log.debug("[LC4jRag] retrieved {} chunks for queryLen={}", contents.size(), query.text().length());
        return contents;
    }
}
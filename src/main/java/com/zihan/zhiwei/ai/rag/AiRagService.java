package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.embedding.CompatibleEmbeddingClient;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D10: RAG 核心
 * candidateK 召回 → 85% 向量 + 15% 字面重排 → topK
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiRagService {

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+");

    private final CompatibleEmbeddingClient embeddingClient;
    private final PgVectorKnowledgeRepository repository;

    @Value("${zhiwei.ai.rag.candidate-k:20}")
    private int defaultCandidateK;

    @Value("${zhiwei.ai.rag.top-k:5}")
    private int defaultTopK;

    @Value("${zhiwei.ai.rag.vector-weight:0.85}")
    private double vectorWeight;

    @Value("${zhiwei.ai.rag.lexical-weight:0.15}")
    private double lexicalWeight;

    public List<RagHit> search(String query) {
        return search(query, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> search(String query, Integer topK, Integer candidateK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("query 不能为空");
        }
        int top = topK == null || topK <= 0 ? defaultTopK : topK;
        int cand = candidateK == null || candidateK <= 0 ? defaultCandidateK : candidateK;
        cand = Math.max(cand, top);

        float[] qVec = embeddingClient.embed(query.trim());
        List<PgVectorKnowledgeRepository.ScoredChunk> recalled = repository.searchByCosine(qVec, cand);
        if (recalled.isEmpty()) {
            return List.of();
        }

        List<RagHit> ranked = new ArrayList<>(recalled.size());
        for (PgVectorKnowledgeRepository.ScoredChunk item : recalled) {
            double v = clamp01(item.vectorScore());
            double l = lexicalScore(query, item.chunk().content());
            double finalScore = vectorWeight * v + lexicalWeight * l;
            ranked.add(new RagHit(item.chunk(), v, l, finalScore));
        }

        ranked.sort(Comparator.comparingDouble(RagHit::finalScore).reversed());
        if (ranked.size() > top) {
            return List.copyOf(ranked.subList(0, top));
        }
        return List.copyOf(ranked);
    }

    public long upsertChunk(Long documentId, String sourceId, String title, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("content 不能为空");
        }
        float[] vec = embeddingClient.embed(content);
        int tokens = Math.max(1, content.length() / 2);
        long id = repository.insert(documentId, sourceId, title, content, vec, tokens);
        log.info("[RAG] upsert chunk id={} documentId={} sourceId={}", id, documentId, sourceId);
        return id;
    }

    public long count() {
        return repository.count();
    }

    static double lexicalScore(String query, String content) {
        Set<String> q = tokenize(query);
        Set<String> c = tokenize(content);
        if (q.isEmpty() || c.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String t : q) {
            if (c.contains(t)) {
                inter++;
            }
        }
        int union = q.size() + c.size() - inter;
        return union == 0 ? 0.0 : (double) inter / (double) union;
    }

    private static Set<String> tokenize(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isBlank()) {
            return set;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        String compact = TOKEN_SPLIT.matcher(normalized).replaceAll("");
        if (compact.length() >= 2) {
            for (int i = 0; i < compact.length() - 1; i++) {
                set.add(compact.substring(i, i + 2));
            }
        } else if (!compact.isEmpty()) {
            set.add(compact);
        }
        for (String part : TOKEN_SPLIT.split(normalized)) {
            if (part != null && !part.isBlank()) {
                set.add(part);
            }
        }
        return set;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
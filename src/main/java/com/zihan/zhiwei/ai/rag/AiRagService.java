package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.embedding.CompatibleEmbeddingClient;
import com.zihan.zhiwei.ai.rag.dto.QueryRewriteResult;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D10+D30+D31: RAG 核心。
 * 查询改写 → 双通道召回 → RRF 融合 → 可选精排 → topK。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiRagService {

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\s\\p{Punct}，。！？；：、\u201c\u201d\u2018\u2019（）【】《》]+");

    private final CompatibleEmbeddingClient embeddingClient;
    private final PgVectorKnowledgeRepository repository;
    private final RrfFusion rrfFusion;
    private final RagReranker reranker;
    private final QueryRewriter queryRewriter;
    private final MultiQueryAggregator multiQueryAggregator;

    @Value("${zhiwei.ai.rag.candidate-k:20}")
    private int defaultCandidateK;

    @Value("${zhiwei.ai.rag.top-k:5}")
    private int defaultTopK;

    @Value("${zhiwei.ai.rag.rrf-k:60}")
    private double rrfK;

    @Value("${zhiwei.ai.rag.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${zhiwei.ai.rag.keyword-weight:0.5}")
    private double keywordWeight;

    public AiRagService(CompatibleEmbeddingClient embeddingClient,
                        PgVectorKnowledgeRepository repository,
                        RrfFusion rrfFusion,
                        RagReranker reranker,
                        QueryRewriter queryRewriter,
                        MultiQueryAggregator multiQueryAggregator) {
        this.embeddingClient = embeddingClient;
        this.repository = repository;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.queryRewriter = queryRewriter;
        this.multiQueryAggregator = multiQueryAggregator;
    }

    // ==================== D31: 带查询改写的检索 ====================

    public List<RagHit> searchWithRewrite(String query, String historyContext) {
        return searchWithRewrite(query, historyContext, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> searchWithRewrite(String query, String historyContext,
                                           Integer topK, Integer candidateK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("query 不能为空");
        }
        int top = topK == null || topK <= 0 ? defaultTopK : topK;
        int cand = candidateK == null || candidateK <= 0 ? defaultCandidateK : candidateK;
        cand = Math.max(cand, top);

        QueryRewriteResult rewriteResult = queryRewriter.rewrite(query, historyContext);
        List<String> queries = rewriteResult.allQueries();

        if (queries.size() == 1) {
            return singleQuerySearch(queries.get(0), top, cand);
        }

        Map<String, List<RagHit>> resultsMap = new LinkedHashMap<>();
        for (String q : queries) {
            try {
                resultsMap.put(q, singleQuerySearch(q, top, cand));
            } catch (Exception e) {
                log.warn("[RAG] sub-query failed: q='{}' err={}", q, e.getMessage());
            }
        }
        if (resultsMap.isEmpty()) {
            return singleQuerySearch(query, top, cand);
        }
        List<RagHit> aggregated = multiQueryAggregator.aggregate(resultsMap, top);
        log.info("[RAG] multi-query: original='{}' subQ={} merged={}",
                query, queries.size(), aggregated.size());
        return aggregated;
    }

    // ==================== 原有检索入口（兼容） ====================

    public List<RagHit> search(String query) {
        return searchWithRewrite(query, null, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> search(String query, Integer topK, Integer candidateK) {
        return searchWithRewrite(query, null, topK, candidateK);
    }

    // ==================== 单查询检索 ====================

    private List<RagHit> singleQuerySearch(String query, int top, int cand) {
        float[] qVec = embeddingClient.embed(query.trim());

        List<PgVectorKnowledgeRepository.ScoredChunk> vectorResults =
                repository.searchByCosine(qVec, cand);
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        List<PgVectorKnowledgeRepository.ScoredChunk> keywordResults = List.of();
        try {
            keywordResults = repository.searchByKeyword(query.trim(), cand);
        } catch (Exception e) {
            log.warn("[RAG] keyword search failed: {}", e.getMessage());
        }

        List<RrfFusion.FusedHit> fused = rrfFusion.fuse(
                vectorResults, keywordResults, rrfK, vectorWeight, keywordWeight, top);

        List<RagHit> rrfHits = new ArrayList<>(fused.size());
        for (RrfFusion.FusedHit fh : fused) {
            rrfHits.add(new RagHit(
                    fh.chunk(),
                    clip(fh.vectorScore()),
                    clip(fh.keywordScore()),
                    clip(fh.rrfScore())
            ));
        }
        rrfHits.sort(Comparator.comparingDouble(RagHit::finalScore).reversed());

        List<RagHit> result = reranker.rerank(query, rrfHits);

        if (result.size() > top) {
            result = List.copyOf(result.subList(0, top));
        } else {
            result = List.copyOf(result);
        }

        log.debug("[RAG] query='{}' vector={} keyword={} fused={} top={}",
                query, vectorResults.size(), keywordResults.size(), fused.size(), result.size());
        return result;
    }

    // ==================== 写入 ====================

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

    // ==================== 工具方法 ====================

    static double lexicalScore(String query, String content) {
        Set<String> q = tokenize(query);
        Set<String> c = tokenize(content);
        if (q.isEmpty() || c.isEmpty()) return 0.0;
        int inter = 0;
        for (String t : q) {
            if (c.contains(t)) inter++;
        }
        int union = q.size() + c.size() - inter;
        return union == 0 ? 0.0 : (double) inter / (double) union;
    }

    static Set<String> tokenize(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isBlank()) return set;
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
            if (part != null && !part.isBlank()) set.add(part);
        }
        return set;
    }

    private static double clip(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}

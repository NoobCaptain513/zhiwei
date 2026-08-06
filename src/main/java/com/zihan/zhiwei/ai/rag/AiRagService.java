package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.embedding.CompatibleEmbeddingClient;
import com.zihan.zhiwei.ai.embedding.EmbeddingClient;
import com.zihan.zhiwei.ai.embedding.EmbeddingClientSelector;
import com.zihan.zhiwei.ai.knowledge.TokenCounter;
import com.zihan.zhiwei.ai.rag.dto.QueryRewriteResult;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * D10+D30+D31: RAG 核心。
 * 查询改写 → 双通道召回 → RRF 融合 → 可选精排 → topK。
 * <p>
 * FIX-5: 多子查询由串行 for 循环改为虚拟线程并行检索。
 * FIX-8(配套): 新增 upsertChunksBatch() 批量 embedding + 批量入库。
 * FIX-9: token 计数从 content.length()/2 改为 TokenCounter（jtokkit BPE）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiRagService {

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\s\\p{Punct}，。！？；：、\u201c\u201d\u2018\u2019（）【】《》]+");

    private final CompatibleEmbeddingClient embeddingClient;
    private final EmbeddingClientSelector embeddingSelector;
    private final PgVectorKnowledgeRepository repository;
    private final RrfFusion rrfFusion;
    private final RagReranker reranker;
    private final QueryRewriter queryRewriter;
    private final MultiQueryAggregator multiQueryAggregator;
    private final TokenCounter tokenCounter;

    /** FIX-5: 子查询并行执行器 */
    private final ExecutorService subQueryExecutor = Executors.newVirtualThreadPerTaskExecutor();

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

    @Value("${zhiwei.ai.rag.sub-query-timeout-ms:8000}")
    private long subQueryTimeoutMs;

    public AiRagService(CompatibleEmbeddingClient embeddingClient,
                        PgVectorKnowledgeRepository repository,
                        RrfFusion rrfFusion,
                        RagReranker reranker,
                        QueryRewriter queryRewriter,
                        MultiQueryAggregator multiQueryAggregator,
                        TokenCounter tokenCounter,
                        EmbeddingClientSelector embeddingSelector) {
        this.embeddingClient = embeddingClient;
        this.embeddingSelector = embeddingSelector;
        this.repository = repository;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.queryRewriter = queryRewriter;
        this.multiQueryAggregator = multiQueryAggregator;
        this.tokenCounter = tokenCounter;
    }

    @PreDestroy
    public void shutdown() {
        subQueryExecutor.close();
    }

    // ==================== D31: 带查询改写的检索 ====================

    public List<RagHit> searchWithRewrite(String query, String historyContext) {
        return searchWithRewrite(query, historyContext, null, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> searchWithRewrite(String query, String historyContext,
                                           Integer topK, Integer candidateK) {
        return searchWithRewrite(query, historyContext, null, topK, candidateK);
    }

    /**
     * 带 Provider 参数的检索方法（根据 Provider 动态选择 Embedding）
     * @param query 查询文本
     * @param historyContext 历史上下文
     * @param provider Provider 名称（如 "ollama", "native-dashscope"），null 表示默认
     * @param topK 最终返回数量
     * @param candidateK 候选数量
     * @return 检索结果
     */
    public List<RagHit> searchWithRewrite(String query, String historyContext,
                                           String provider, Integer topK, Integer candidateK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("query 不能为空");
        }
        int top = topK == null || topK <= 0 ? defaultTopK : topK;
        int cand = candidateK == null || candidateK <= 0 ? defaultCandidateK : candidateK;
        cand = Math.max(cand, top);

        QueryRewriteResult rewriteResult = queryRewriter.rewrite(query, historyContext);
        List<String> queries = rewriteResult.allQueries();

        if (queries.size() == 1) {
            return singleQuerySearch(queries.get(0), provider, top, cand);
        }

        // FIX-5: 并行子查询检索
        final int fTop = top;
        final int fCand = cand;
        Map<String, Future<List<RagHit>>> futures = new LinkedHashMap<>();
        for (String q : queries) {
            futures.put(q, subQueryExecutor.submit(() -> singleQuerySearch(q, provider, fTop, fCand)));
        }

        Map<String, List<RagHit>> resultsMap = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + Math.max(1000, subQueryTimeoutMs);
        for (Map.Entry<String, Future<List<RagHit>>> entry : futures.entrySet()) {
            long remaining = deadline - System.currentTimeMillis();
            try {
                List<RagHit> hits = entry.getValue()
                        .get(Math.max(1, remaining), TimeUnit.MILLISECONDS);
                resultsMap.put(entry.getKey(), hits);
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                log.warn("[RAG] sub-query timeout: q='{}'", entry.getKey());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entry.getValue().cancel(true);
                log.warn("[RAG] sub-query interrupted: q='{}'", entry.getKey());
                break;
            } catch (Exception e) {
                log.warn("[RAG] sub-query failed: q='{}' err={}", entry.getKey(),
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }

        if (resultsMap.isEmpty()) {
            return singleQuerySearch(query, provider, top, cand);
        }
        List<RagHit> aggregated = multiQueryAggregator.aggregate(resultsMap, top);
        log.info("[RAG] multi-query(parallel): original='{}' subQ={} ok={} merged={}",
                query, queries.size(), resultsMap.size(), aggregated.size());
        return aggregated;
    }

    // ==================== 原有检索入口（兼容） ====================

    public List<RagHit> search(String query) {
        return searchWithRewrite(query, null, null, defaultTopK, defaultCandidateK);
    }

    public List<RagHit> search(String query, Integer topK, Integer candidateK) {
        return searchWithRewrite(query, null, null, topK, candidateK);
    }

    // ==================== 单查询检索 ====================

    private List<RagHit> singleQuerySearch(String query, String provider, int top, int cand) {
        // 根据 Provider 选择 Embedding 客户端
        EmbeddingClient client = (provider != null && embeddingSelector != null)
                ? embeddingSelector.select(provider)
                : embeddingClient;
        float[] qVec = client.embed(query.trim());

        // 根据 Provider 选择向量列名
        String vectorColumn = (provider != null && embeddingSelector != null)
                ? embeddingSelector.getVectorColumn(provider)
                : "embedding";

        List<PgVectorKnowledgeRepository.ScoredChunk> vectorResults =
                repository.searchByCosine(qVec, cand, vectorColumn);
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
        // FIX-9: 真实 BPE 计数替代 content.length()/2
        int tokens = Math.max(1, tokenCounter.count(content));
        long id = repository.insert(documentId, sourceId, title, content, vec, tokens);
        log.info("[RAG] upsert chunk id={} documentId={} sourceId={}", id, documentId, sourceId);
        return id;
    }

    /**
     * FIX-8(配套): 批量写入。
     */
    public int upsertChunksBatch(Long documentId, List<ChunkPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return 0;
        }
        List<String> texts = payloads.stream().map(ChunkPayload::content).toList();
        List<float[]> vectors = embeddingClient.embedBatch(texts);
        if (vectors.size() != payloads.size()) {
            throw new BusinessException("embedding 批量返回数量不匹配: "
                    + vectors.size() + " != " + payloads.size());
        }
        List<PgVectorKnowledgeRepository.InsertRow> rows = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            ChunkPayload p = payloads.get(i);
            rows.add(new PgVectorKnowledgeRepository.InsertRow(
                    documentId, p.sourceId(), p.title(), p.content(),
                    vectors.get(i), Math.max(1, tokenCounter.count(p.content()))));
        }
        int inserted = repository.batchInsert(rows);
        log.info("[RAG] batch upsert documentId={} chunks={} inserted={}",
                documentId, payloads.size(), inserted);
        return inserted;
    }

    public record ChunkPayload(String sourceId, String title, String content) {}

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

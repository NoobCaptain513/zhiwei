package com.zihan.zhiwei.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.rag.dto.QueryRewriteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * D31: 查询改写器。
 * <p>
 * FIX-6: Caffeine 本地缓存改写结果。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QueryRewriter {

    private final ModelProviderRouter modelProviderRouter;
    private final ObjectMapper objectMapper;

    @Value("${zhiwei.ai.rag.rewrite-enabled:true}")
    private boolean enabled;

    @Value("${zhiwei.ai.rag.rewrite-model:qwen-plus}")
    private String rewriteModel;

    @Value("${zhiwei.ai.rag.rewrite-max-sub-questions:3}")
    private int maxSubQuestions;

    /** FIX-6: 缓存容量与 TTL 可配 */
    private final Cache<String, QueryRewriteResult> rewriteCache;

    static final String REWRITE_SYSTEM_PROMPT =
            "你是查询改写助手。将用户查询中的指代词（\"它\"\"这个\"\"那个服务\"等）替换为具体实体，生成自包含的完整查询。"
                    + "如果查询包含多个问题或主题，拆分为2-3个独立子问题。"
                    + "只返回JSON：{\"rewritten\":\"完整查询\",\"subQuestions\":[\"子问题1\"]}。subQuestions为空数组表示不需分解。";

    public QueryRewriter(ModelProviderRouter modelProviderRouter,
                         ObjectMapper objectMapper,
                         @Value("${zhiwei.ai.rag.rewrite-cache-size:1000}") int cacheSize,
                         @Value("${zhiwei.ai.rag.rewrite-cache-ttl-seconds:600}") long cacheTtlSeconds) {
        this.modelProviderRouter = modelProviderRouter;
        this.objectMapper = objectMapper;
        this.rewriteCache = Caffeine.newBuilder()
                .maximumSize(Math.max(16, cacheSize))
                .expireAfterWrite(Duration.ofSeconds(Math.max(30, cacheTtlSeconds)))
                .recordStats()
                .build();
    }

    public QueryRewriteResult rewrite(String userMessage, String historyContext) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return new QueryRewriteResult(
                    userMessage != null ? userMessage : "", userMessage, List.of());
        }

        if (isSimpleQuery(userMessage)) {
            log.debug("[Rewrite] skip simple query='{}'", userMessage);
            return new QueryRewriteResult(userMessage, userMessage, List.of());
        }

        // FIX-6: 缓存快路径
        String cacheKey = cacheKey(userMessage, historyContext);
        QueryRewriteResult cached = rewriteCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("[Rewrite] cache hit query='{}' (stats: {})",
                    userMessage, rewriteCache.stats());
            return cached;
        }

        try {
            String prompt = buildRewritePrompt(userMessage, historyContext);
            ProviderChatRequest request = new ProviderChatRequest(rewriteModel, List.of(
                    new ProviderChatMessage("system", REWRITE_SYSTEM_PROMPT),
                    new ProviderChatMessage("user", prompt)
            ));

            ProviderChatResponse response = modelProviderRouter.chatWithFailover(request);
            QueryRewriteResult result = parseResult(userMessage, response.content());
            log.info("[Rewrite] '{}' -> rewritten='{}' subQ={}",
                    userMessage, result.rewritten(), result.subQuestions());

            // FIX-6: 只缓存"真的改写成功"的结果
            if (!result.rewritten().equals(userMessage) || !result.subQuestions().isEmpty()) {
                rewriteCache.put(cacheKey, result);
            }
            return result;

        } catch (Exception e) {
            log.warn("[Rewrite] failed, fallback to original: {}", e.getMessage());
            return new QueryRewriteResult(userMessage, userMessage, List.of());
        }
    }

    public QueryRewriteResult rewrite(String userMessage) {
        return rewrite(userMessage, null);
    }

    // ==================== 内部方法 ====================

    private static String cacheKey(String query, String historyContext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(query.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x01);
            if (historyContext != null) {
                digest.update(historyContext.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return query + "\u0001" + (historyContext == null ? "" : historyContext);
        }
    }

    private String buildRewritePrompt(String userMessage, String historyContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户查询：").append(userMessage).append("\n");
        if (historyContext != null && !historyContext.isBlank()) {
            sb.append("对话上下文：\n").append(historyContext).append("\n");
        }
        sb.append("\n请改写查询。");
        return sb.toString();
    }

    private QueryRewriteResult parseResult(String original, String content) {
        if (content == null || content.isBlank()) {
            return new QueryRewriteResult(original, original, List.of());
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new QueryRewriteResult(original, original, List.of());
            }
            String json = content.substring(start, end + 1);
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});

            String rewritten = map.get("rewritten") instanceof String s && !s.isBlank() ? s : original;

            List<String> subQuestions = List.of();
            if (map.get("subQuestions") instanceof List<?> list) {
                subQuestions = list.stream()
                        .filter(item -> item instanceof String)
                        .map(String.class::cast)
                        .filter(s -> !s.isBlank())
                        .limit(maxSubQuestions)
                        .toList();
            }
            return new QueryRewriteResult(original, rewritten, subQuestions);
        } catch (Exception e) {
            log.debug("[Rewrite] parse failed: {}", e.getMessage());
            return new QueryRewriteResult(original, original, List.of());
        }
    }

    private boolean isSimpleQuery(String message) {
        String m = message.trim();
        if (m.length() <= 5) {
            return true;
        }
        boolean hasPronoun = m.contains("它") || m.contains("这个")
                || m.contains("那个") || m.contains("上次")
                || m.contains("之前");
        boolean hasConjunction = m.contains("和") || m.contains("以及")
                || m.contains("还有") || m.contains("另外")
                || m.contains("并且") || m.contains("、");
        return !hasPronoun && !hasConjunction;
    }
}

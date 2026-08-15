package com.zihan.zhiwei.ai.usage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
import com.zihan.zhiwei.mapper.AiUsageLogMapper;
import com.zihan.zhiwei.pojo.dto.UsageRecentItem;
import com.zihan.zhiwei.pojo.entity.AiUsageLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * D9: AI 调用用量记录器
 * 每次调用：写 MySQL 持久化。
 * <p>
 * FIX-7 后：Redis 滑动窗口的写入统一由 ProviderMetrics -> RedisMetricsPersistence 负责，
 * 本类不再旁路写 Redis，避免同一次调用被重复写入同一个窗口 key。
 * readRedisWindow 保留为只读工具，读取的是 RedisMetricsPersistence 写入的同一份数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageRecorder {

    public static final String REDIS_WINDOW_KEY_PREFIX = "zhiwei:provider:metrics:window:";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String MODE_CHAT = "chat";

    private final AiUsageLogMapper aiUsageLogMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CostCalibrationInterceptor costCalibrationInterceptor;
    private final ObjectMapper objectMapper;

    /** 记录一次成功（或降级成功）的 AI 调用 */
    public void record(Long conversationId,
                       Long messageId,
                       ProviderChatResponse response,
                       String mode,
                       long latencyMs,
                       boolean degraded) {
        String status = degraded ? STATUS_DEGRADED : STATUS_SUCCESS;
        // FIX: 使用支持 Provider 区分的成本计算方法，确保 Ollama 本地模型零成本
        BigDecimal cost = costCalibrationInterceptor.estimateCostForProvider(
                response.provider(), response.promptTokens(), response.completionTokens());

        AiUsageLog row = new AiUsageLog();
        row.setConversationId(conversationId);
        row.setMessageId(messageId);
        row.setProvider(response.provider());
        row.setModel(response.model());
        row.setMode(mode == null || mode.isBlank() ? MODE_CHAT : mode);
        row.setPromptTokens(response.promptTokens());
        row.setCompletionTokens(response.completionTokens());
        row.setTotalTokens(response.totalTokens());
        row.setCost(cost);
        row.setLatencyMs(Math.max(0L, latencyMs));
        row.setStatus(status);
        row.setCreateTime(LocalDateTime.now());
        aiUsageLogMapper.insert(row);

        log.debug("[Usage] recorded id={} provider={} mode={} status={} latencyMs={} cost={}",
                row.getId(), row.getProvider(), row.getMode(), row.getStatus(), row.getLatencyMs(), cost);
    }

    /** 兼容旧签名 */
    public void record(Long conversationId, Long messageId, ProviderChatResponse response) {
        record(conversationId, messageId, response, MODE_CHAT, 0L, false);
    }

    /** 记录失败调用（无 response 时） */
    public void recordFailure(Long conversationId,
                              String provider,
                              String model,
                              String mode,
                              long latencyMs,
                              String errorMsg) {
        AiUsageLog row = new AiUsageLog();
        row.setConversationId(conversationId);
        row.setProvider(provider);
        row.setModel(model);
        row.setMode(mode == null || mode.isBlank() ? MODE_CHAT : mode);
        row.setPromptTokens(0);
        row.setCompletionTokens(0);
        row.setTotalTokens(0);
        row.setCost(BigDecimal.ZERO);
        row.setLatencyMs(Math.max(0L, latencyMs));
        row.setStatus(STATUS_FAILED);
        row.setCreateTime(LocalDateTime.now());
        aiUsageLogMapper.insert(row);

        log.warn("[Usage] failure provider={} latencyMs={} err={}", provider, latencyMs, errorMsg);
    }

    /** 查询最近 N 条用量明细 */
    public List<UsageRecentItem> recent(int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        List<AiUsageLog> rows = aiUsageLogMapper.selectList(
                new QueryWrapper<AiUsageLog>()
                        .orderByDesc("create_time")
                        .orderByDesc("id")
                        .last("LIMIT " + size)
        );
        List<UsageRecentItem> items = new ArrayList<>(rows.size());
        for (AiUsageLog row : rows) {
            items.add(new UsageRecentItem(
                    row.getId(),
                    row.getConversationId(),
                    row.getMessageId(),
                    row.getProvider(),
                    row.getModel(),
                    row.getMode(),
                    row.getPromptTokens(),
                    row.getCompletionTokens(),
                    row.getTotalTokens(),
                    row.getCost(),
                    row.getLatencyMs(),
                    row.getStatus(),
                    row.getCreateTime()
            ));
        }
        return items;
    }

    public List<MetricSample> readRedisWindow(String provider) {
        String key = REDIS_WINDOW_KEY_PREFIX + provider;
        try {
            List<String> raw = stringRedisTemplate.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<MetricSample> samples = new ArrayList<>(raw.size());
            for (String item : raw) {
                samples.add(objectMapper.readValue(item, MetricSample.class));
            }
            return samples;
        } catch (Exception ex) {
            log.debug("[Usage] read redis window failed provider={}, err={}", provider, ex.getMessage());
            return Collections.emptyList();
        }
    }

    public record MetricSample(boolean success, long latencyMs, double cost, long ts) {}
}
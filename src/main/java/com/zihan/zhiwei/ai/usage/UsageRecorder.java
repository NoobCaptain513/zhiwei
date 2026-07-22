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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * D9: AI 调用用量记录器
 * 每次调用：写 MySQL 持久化 + 更新 Redis 滑动窗口指标（喂给路由器）
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

    private static final int DEFAULT_WINDOW_SIZE = 100;

    private final AiUsageLogMapper aiUsageLogMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CostCalibrationInterceptor costCalibrationInterceptor;
    private final ObjectMapper objectMapper;

    @Value("${zhiwei.ai.router.metrics-window-size:100}")
    private int windowSize;

    /** 记录一次成功（或降级成功）的 AI 调用 */
    public void record(Long conversationId,
                       Long messageId,
                       ProviderChatResponse response,
                       String mode,
                       long latencyMs,
                       boolean degraded) {
        String status = degraded ? STATUS_DEGRADED : STATUS_SUCCESS;
        BigDecimal cost = costCalibrationInterceptor.estimateCost(
                response.promptTokens(), response.completionTokens());

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

        // Redis 滑动窗口：供跨实例 / 运维观测，也作为路由闭环数据源
        pushRedisSample(response.provider(), true, latencyMs, cost.doubleValue());

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

        pushRedisSample(provider, false, latencyMs, 0.0);
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

    private void pushRedisSample(String provider, boolean success, long latencyMs, double cost) {
        String key = REDIS_WINDOW_KEY_PREFIX + provider;
        int cap = windowSize > 0 ? windowSize : DEFAULT_WINDOW_SIZE;
        try {
            String payload = objectMapper.writeValueAsString(new MetricSample(
                    success, latencyMs, cost, System.currentTimeMillis()));
            stringRedisTemplate.opsForList().leftPush(key, payload);
            stringRedisTemplate.opsForList().trim(key, 0, cap - 1L);
            stringRedisTemplate.expire(key, 24, TimeUnit.HOURS);
        } catch (Exception ex) {
            log.warn("[Usage] write redis window failed provider={}, err={}", provider, ex.getMessage());
        }
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
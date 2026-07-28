package com.zihan.zhiwei.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FIX-7: Redis 实现。
 * 复用 UsageRecorder 已有的窗口 key 和样本 JSON 结构，
 * Redis 成为指标的唯一事实来源。所有操作 best-effort。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMetricsPersistence implements MetricsPersistence {

    public static final String KEY_PREFIX = "zhiwei:provider:metrics:window:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${zhiwei.ai.router.metrics-window-size:100}")
    private int windowSize;

    @Override
    public void push(String provider, boolean success, long latencyMs) {
        String key = KEY_PREFIX + provider;
        try {
            String payload = objectMapper.writeValueAsString(
                    new Sample(success, latencyMs, 0.0, System.currentTimeMillis()));
            redisTemplate.opsForList().leftPush(key, payload);
            redisTemplate.opsForList().trim(key, 0, Math.max(1, windowSize) - 1L);
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("[MetricsPersistence] push failed provider={}: {}", provider, e.getMessage());
        }
    }

    @Override
    public List<PersistedSample> load(String provider) {
        String key = KEY_PREFIX + provider;
        try {
            List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<PersistedSample> samples = new ArrayList<>(raw.size());
            for (String item : raw) {
                JsonNode node = objectMapper.readTree(item);
                samples.add(new PersistedSample(
                        node.path("success").asBoolean(false),
                        node.path("latencyMs").asLong(0),
                        node.path("ts").asLong(0)));
            }
            Collections.reverse(samples);
            return samples;
        } catch (Exception e) {
            log.debug("[MetricsPersistence] load failed provider={}: {}", provider, e.getMessage());
            return List.of();
        }
    }

    private record Sample(boolean success, long latencyMs, double cost, long ts) {}
}

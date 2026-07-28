package com.zihan.zhiwei.ai.provider.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.failover.FailoverEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * D16: 降级事件日志。
 * <p>
 * FIX-13: 从"纯内存环形缓冲"升级为"内存快路径 + Redis 持久化"。
 */
@Slf4j
@Component
public class FailoverEventLog {

    private static final String REDIS_KEY = "zhiwei:failover:events";

    @Value("${zhiwei.ai.health.event-log-size:200}")
    private int maxSize;

    @Value("${zhiwei.ai.health.event-redis-size:1000}")
    private int redisMaxSize;

    @Value("${zhiwei.ai.health.event-redis-ttl-days:7}")
    private long redisTtlDays;

    private final ConcurrentLinkedDeque<FailoverEvent> events = new ConcurrentLinkedDeque<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instance;

    public FailoverEventLog(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        this.objectMapper = mapper != null ? mapper : new ObjectMapper();
        this.instance = resolveInstance();
    }

    /** 单测兼容：无持久化 */
    public FailoverEventLog() {
        this.redisTemplate = null;
        this.objectMapper = new ObjectMapper();
        this.instance = resolveInstance();
    }

    public void record(FailoverEvent event) {
        events.addLast(event);
        while (events.size() > maxSize) {
            events.pollFirst();
        }
        log.info("[FailoverEvent] {} → {} reason={} at={}",
                event.fromProvider(), event.toProvider(), event.reason(), event.occurredAt());
        pushRedis(event);
    }

    public List<FailoverEvent> recent(int limit) {
        int n = Math.min(limit, events.size());
        int from = events.size() - n;
        return events.stream().skip(from).limit(n).collect(java.util.stream.Collectors.toList());
    }

    public List<FailoverEvent> recent() {
        return recent(20);
    }

    /** FIX-13: 跨实例全局视图（读 Redis） */
    public List<FailoverEvent> recentGlobal(int limit) {
        if (redisTemplate == null) {
            return recent(limit);
        }
        try {
            List<String> raw = redisTemplate.opsForList()
                    .range(REDIS_KEY, 0, Math.max(1, limit) - 1L);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<FailoverEvent> result = new ArrayList<>(raw.size());
            for (String item : raw) {
                FailoverEvent evt = deserialize(item);
                if (evt != null) {
                    result.add(evt);
                }
            }
            Collections.reverse(result);
            return result;
        } catch (Exception e) {
            log.debug("[FailoverEvent] read redis failed: {}", e.getMessage());
            return recent(limit);
        }
    }

    /** FIX-13: 启动水合 */
    @EventListener(ApplicationReadyEvent.class)
    public void hydrateOnStartup() {
        if (redisTemplate == null || !events.isEmpty()) {
            return;
        }
        try {
            List<FailoverEvent> persisted = recentGlobal(maxSize);
            persisted.forEach(events::addLast);
            if (!persisted.isEmpty()) {
                log.info("[FailoverEvent] hydrated {} events from redis", persisted.size());
            }
        } catch (Exception e) {
            log.warn("[FailoverEvent] hydrate failed: {}", e.getMessage());
        }
    }

    public void clear() {
        events.clear();
    }

    private void pushRedis(FailoverEvent event) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(new PersistedEvent(
                    event.fromProvider(), event.toProvider(), event.reason(),
                    event.occurredAt().toEpochMilli(), instance));
            redisTemplate.opsForList().leftPush(REDIS_KEY, payload);
            redisTemplate.opsForList().trim(REDIS_KEY, 0, Math.max(1, redisMaxSize) - 1L);
            redisTemplate.expire(REDIS_KEY, redisTtlDays, TimeUnit.DAYS);
        } catch (Exception e) {
            log.debug("[FailoverEvent] push redis failed: {}", e.getMessage());
        }
    }

    private FailoverEvent deserialize(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String reason = node.path("reason").asText("");
            String inst = node.path("instance").asText("");
            if (!inst.isBlank() && !inst.equals(instance)) {
                reason = "[" + inst + "] " + reason;
            }
            return new FailoverEvent(
                    node.path("from").asText(""),
                    node.path("to").asText(""),
                    reason,
                    Instant.ofEpochMilli(node.path("atMs").asLong(0)));
        } catch (Exception e) {
            log.debug("[FailoverEvent] deserialize failed: {}", e.getMessage());
            return null;
        }
    }

    private record PersistedEvent(String from, String to, String reason, long atMs, String instance) {}

    private static String resolveInstance() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

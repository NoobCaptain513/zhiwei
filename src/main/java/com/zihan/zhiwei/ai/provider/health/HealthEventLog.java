package com.zihan.zhiwei.ai.provider.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * D16: 健康检测事件日志（内存环形缓冲）。
 * 保留最近 N 条事件，支持按 provider 查询。
 */
@Slf4j
@Component
public class HealthEventLog {

    @Value("${zhiwei.ai.health.event-log-size:200}")
    private int maxSize;

    private final ConcurrentLinkedDeque<HealthEvent> events = new ConcurrentLinkedDeque<>();

    public void record(HealthEvent event) {
        events.addLast(event);
        while (events.size() > maxSize) {
            events.pollFirst();
        }
        log.debug("[HealthEvent] provider={} healthy={} cold={} latency={}ms",
                event.getProvider(), event.isHealthy(), event.isColdStart(), event.getLatencyMs());
    }

    /** 获取最近 N 条事件 */
    public List<HealthEvent> recent(int limit) {
        int n = Math.min(limit, events.size());
        return new ArrayList<>(events).subList(events.size() - n, events.size());
    }

    /** 获取最近事件 */
    public List<HealthEvent> recent() {
        return recent(20);
    }

    /** 按 provider 过滤 */
    public List<HealthEvent> byProvider(String provider, int limit) {
        return events.stream()
                .filter(e -> e.getProvider().equals(provider))
                .collect(Collectors.toList())
                .reversed().stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** 每个 provider 的最近一条 */
    public Map<String, HealthEvent> latestPerProvider() {
        return events.stream()
                .collect(Collectors.toMap(
                        HealthEvent::getProvider,
                        e -> e,
                        (a, b) -> b
                ));
    }

    public void clear() {
        events.clear();
    }
}
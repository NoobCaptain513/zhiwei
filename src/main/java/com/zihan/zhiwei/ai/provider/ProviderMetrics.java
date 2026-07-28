package com.zihan.zhiwei.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider 滑动窗口指标。
 * <p>
 * FIX-7: 内存窗口保留为快路径，但不再是唯一数据：
 * - 每次 record 旁路写出到 MetricsPersistence（Redis，best-effort）；
 * - 启动时由 MetricsHydrationRunner 从 Redis 回放历史样本。
 * 架构：内存 = 读缓存，Redis = 事实来源（write-through + startup warm-up）。
 * <p>
 * 兼容性：保留无参构造器，现有单测 new ProviderMetrics() 不受影响。
 */
@Slf4j
@Component
public class ProviderMetrics {

    private static final int WINDOW_SIZE = 100;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final MetricsPersistence persistence;

    /** 单测兼容：无持久化 */
    public ProviderMetrics() {
        this.persistence = null;
    }

    /** Spring 注入 */
    public ProviderMetrics(ObjectProvider<MetricsPersistence> persistenceProvider) {
        this.persistence = persistenceProvider.getIfAvailable();
    }

    public void recordSuccess(String provider, long latencyMs) {
        windows.computeIfAbsent(provider, key -> new Window()).record(true, latencyMs);
        pushOut(provider, true, latencyMs);
    }

    public void recordFailure(String provider, long latencyMs) {
        windows.computeIfAbsent(provider, key -> new Window()).record(false, latencyMs);
        pushOut(provider, false, latencyMs);
    }

    /** FIX-7: 启动水合 */
    public void hydrate(String provider, List<MetricsPersistence.PersistedSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        Window window = windows.computeIfAbsent(provider, key -> new Window());
        if (window.size() > 0) {
            log.debug("[Metrics] skip hydrate provider={}, window not empty", provider);
            return;
        }
        for (MetricsPersistence.PersistedSample s : samples) {
            window.record(s.success(), s.latencyMs());
        }
        Snapshot snap = window.snapshot(provider);
        log.info("[Metrics] hydrated provider={} samples={} successRate={} avgLatencyMs={}",
                provider, samples.size(),
                String.format("%.2f", snap.successRate()), snap.avgLatencyMs());
    }

    private void pushOut(String provider, boolean success, long latencyMs) {
        if (persistence != null) {
            persistence.push(provider, success, latencyMs);
        }
    }

    public Snapshot snapshot(String provider) {
        Window window = windows.get(provider);
        if (window == null) {
            return Snapshot.empty(provider);
        }
        return window.snapshot(provider);
    }

    public record Snapshot(
            String provider,
            long totalCalls,
            long successCalls,
            long failureCalls,
            double successRate,
            long avgLatencyMs,
            long lastLatencyMs
    ) {
        public static Snapshot empty(String provider) {
            return new Snapshot(provider, 0, 0, 0, 1.0, 0, 0);
        }
    }

    private static final class Window {
        private final Deque<Sample> samples = new ArrayDeque<>(WINDOW_SIZE);
        private final AtomicLong totalCalls = new AtomicLong();
        private final AtomicLong successCalls = new AtomicLong();
        private final AtomicLong failureCalls = new AtomicLong();
        private volatile long lastLatencyMs;

        synchronized int size() {
            return samples.size();
        }

        synchronized void record(boolean success, long latencyMs) {
            if (samples.size() >= WINDOW_SIZE) {
                Sample removed = samples.removeFirst();
                totalCalls.decrementAndGet();
                if (removed.success()) {
                    successCalls.decrementAndGet();
                } else {
                    failureCalls.decrementAndGet();
                }
            }
            samples.addLast(new Sample(success, latencyMs));
            totalCalls.incrementAndGet();
            if (success) {
                successCalls.incrementAndGet();
            } else {
                failureCalls.incrementAndGet();
            }
            lastLatencyMs = latencyMs;
        }

        synchronized Snapshot snapshot(String provider) {
            long total = totalCalls.get();
            long success = successCalls.get();
            long failure = failureCalls.get();
            long avgLatency = 0;
            if (!samples.isEmpty()) {
                long sum = 0;
                for (Sample sample : samples) {
                    sum += sample.latencyMs();
                }
                avgLatency = sum / samples.size();
            }
            double successRate = total == 0 ? 1.0 : (double) success / (double) total;
            return new Snapshot(provider, total, success, failure, successRate, avgLatency, lastLatencyMs);
        }
    }

    private record Sample(boolean success, long latencyMs) {}
}

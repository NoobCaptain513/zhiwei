package com.zihan.zhiwei.ai.provider;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider 滑动窗口指标。
 * <p>
 * 第一周：内存统计最近 N 次调用的成功率与平均延迟，供路由状态接口展示。
 * 后续可替换为 Micrometer / Redis 聚合。
 */
@Component
public class ProviderMetrics {

    private static final int WINDOW_SIZE = 100;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void recordSuccess(String provider, long latencyMs) {
        windows.computeIfAbsent(provider, key -> new Window()).record(true, latencyMs);
    }

    public void recordFailure(String provider, long latencyMs) {
        windows.computeIfAbsent(provider, key -> new Window()).record(false, latencyMs);
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

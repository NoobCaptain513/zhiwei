package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.zihan.zhiwei.ai.provider.failover.FailoverHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D7: Provider 健康监控。
 * 定期探测各 Provider 可用性，并结合指标窗口给出健康快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthMonitor {

    private final List<ModelProvider> providers;
    private final ProviderMetrics providerMetrics;
    private final FailoverHandler failoverHandler;

    private final Map<String, Boolean> availability = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${zhiwei.ai.health.check-interval-ms:30000}")
    public void probe() {
        for (ModelProvider provider : providers) {
            boolean healthy = !failoverHandler.isCircuitOpen(provider.name());
            availability.put(provider.name(), healthy);
            log.debug("health probe provider={}, healthy={}", provider.name(), healthy);
        }
    }

    public boolean isHealthy(String provider) {
        return availability.getOrDefault(provider, true)
                && !failoverHandler.isCircuitOpen(provider);
    }

    public List<ProviderHealth> snapshot() {
        return providers.stream().map(provider -> {
            ProviderMetrics.Snapshot metrics = providerMetrics.snapshot(provider.name());
            boolean healthy = isHealthy(provider.name());
            long latencyMs = metrics.lastLatencyMs() > 0
                    ? metrics.lastLatencyMs()
                    : metrics.avgLatencyMs();
            String message = healthy
                    ? String.format("ok, successRate=%.2f, calls=%d, circuit=%s",
                    metrics.successRate(), metrics.totalCalls(), failoverHandler.stateOf(provider.name()))
                    : String.format("unhealthy, circuit=%s", failoverHandler.stateOf(provider.name()));
            return new ProviderHealth(provider.name(), healthy, latencyMs, message);
        }).toList();
    }
}
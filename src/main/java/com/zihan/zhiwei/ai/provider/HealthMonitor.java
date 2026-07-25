package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import com.zihan.zhiwei.ai.provider.failover.FailoverHandler;
import com.zihan.zhiwei.ai.provider.probe.FirstPacketProbeConfig;
import com.zihan.zhiwei.ai.provider.probe.ModelProbeService;
import com.zihan.zhiwei.ai.provider.probe.ProbeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D7: Provider 健康监控。
 * 定期检查 CircuitBreaker 状态 + 首包探测缓存，给出复合健康快照。
 * D28: 整合真实 API 探测结果。
 */
@Slf4j
@Component
public class HealthMonitor {

    private final List<ModelProvider> providers;
    private final ProviderMetrics providerMetrics;
    private final FailoverHandler failoverHandler;
    private final ModelProbeService probeService;
    private final FirstPacketProbeConfig probeConfig;

    private final Map<String, Boolean> availability = new ConcurrentHashMap<>();

    public HealthMonitor(List<ModelProvider> providers,
                         ProviderMetrics providerMetrics,
                         FailoverHandler failoverHandler,
                         ModelProbeService probeService,
                         FirstPacketProbeConfig probeConfig) {
        this.providers = providers;
        this.providerMetrics = providerMetrics;
        this.failoverHandler = failoverHandler;
        this.probeService = probeService;
        this.probeConfig = probeConfig;
    }

    @Scheduled(fixedDelayString = "${zhiwei.ai.health.check-interval-ms:30000}")
    public void probe() {
        List<CompletableFuture<Void>> futures = providers.stream()
                .map(provider -> CompletableFuture.runAsync(() -> {
                    try {
                        boolean circuitOk = !failoverHandler.isCircuitOpen(provider.name());
                        boolean probeOk = true;

                        if (probeConfig.isEnabled()) {
                            ProbeResult result = probeService.probe(provider.name());
                            probeOk = result.success();
                            if (!probeOk) {
                                log.warn("[Health] probe failed provider={} error={}",
                                        provider.name(), result.error());
                            }
                        }

                        availability.put(provider.name(), circuitOk && probeOk);
                    } catch (Exception e) {
                        availability.put(provider.name(), false);
                        log.warn("[Health] probe error provider={}", provider.name(), e);
                    }
                }))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.debug("[Health] probe done, availability={}", availability);
    }

    public boolean isHealthy(String provider) {
        boolean circuitOk = !failoverHandler.isCircuitOpen(provider);
        boolean cachedOk = availability.getOrDefault(provider, true);
        return circuitOk && cachedOk;
    }

    public List<ProviderHealth> snapshot() {
        return providers.stream().map(provider -> {
            ProviderMetrics.Snapshot metrics = providerMetrics.snapshot(provider.name());
            boolean healthy = isHealthy(provider.name());
            long latencyMs = metrics.lastLatencyMs() > 0
                    ? metrics.lastLatencyMs()
                    : metrics.avgLatencyMs();
            ProbeResult probeResult = probeService.getCached(provider.name());
            String probeInfo = probeResult != null
                    ? String.format(" probeOk=%s latency=%dms",
                    probeResult.success(), probeResult.latencyMs())
                    : "";
            String message = healthy
                    ? String.format("ok, successRate=%.2f, calls=%d, circuit=%s%s",
                    metrics.successRate(), metrics.totalCalls(),
                    failoverHandler.stateOf(provider.name()), probeInfo)
                    : String.format("unhealthy, circuit=%s%s",
                    failoverHandler.stateOf(provider.name()), probeInfo);
            return new ProviderHealth(provider.name(), healthy, latencyMs, message);
        }).toList();
    }
}

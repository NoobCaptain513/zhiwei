package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.HealthMonitor;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.ai.provider.health.HealthEventLog;
import com.zihan.zhiwei.pojo.dto.RouterMetricsDetail;
import com.zihan.zhiwei.pojo.dto.RouterStatus;
import com.zihan.zhiwei.service.RouterMonitorService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouterMonitorServiceImpl implements RouterMonitorService {

    private final HealthMonitor healthMonitor;
    private final ProviderMetrics providerMetrics;
    private final FailoverEventLog failoverEventLog;
    private final HealthEventLog healthEventLog;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${zhiwei.ai.default-provider:spring-ai-alibaba}")
    private String defaultProvider;

    @Value("${zhiwei.ai.router.failover-chain[0]:spring-ai-alibaba}")
    private String chain0;
    @Value("${zhiwei.ai.router.failover-chain[1]:langchain4j-openai}")
    private String chain1;
    @Value("${zhiwei.ai.router.failover-chain[2]:native-dashscope}")
    private String chain2;

    @Override
    public RouterStatus fullStatus() {
        List<ProviderHealth> health = healthMonitor.snapshot();

        // 最近降级事件
        List<RouterStatus.FailoverEventDto> failovers = failoverEventLog.recent(20).stream()
                .map(e -> RouterStatus.FailoverEventDto.builder()
                        .from(e.fromProvider())
                        .to(e.toProvider())
                        .reason(e.reason())
                        .occurredAt(formatInstant(e.occurredAt()))
                        .build())
                .toList();

        // 成本权重（从 ProviderMetrics 读取快照里的信息，暂用默认值）
        java.util.Map<String, Double> costWeights = new java.util.LinkedHashMap<>();
        for (ProviderHealth h : health) {
            costWeights.put(h.name(), 1.0);
        }

        return RouterStatus.builder()
                .defaultProvider(defaultProvider)
                .failoverChain(List.of(chain0, chain1, chain2))
                .providers(health)
                .recentFailovers(failovers)
                .costWeights(costWeights)
                .build();
    }

    @Override
    public RouterMetricsDetail providerDetail(String providerName) {
        ProviderMetrics.Snapshot metrics = providerMetrics.snapshot(providerName);
        ProviderHealth health = findHealth(healthMonitor.snapshot(), providerName);

        // 熔断器状态
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(providerName);
        CircuitBreaker.State cbState = cb.getState();
        float cbFailureRate = cb.getMetrics().getFailureRate();

        // 最近健康事件 → 决策日志
        List<RouterMetricsDetail.DecisionLog> decisions = new ArrayList<>();
        for (var event : healthEventLog.recent(50)) {
            if (providerName.equals(event.getProvider())) {
                decisions.add(RouterMetricsDetail.DecisionLog.builder()
                        .action(event.isHealthy() ? "HEALTHY" : "UNHEALTHY")
                        .detail(event.getMessage())
                        .timestamp(formatInstant(event.getOccurredAt()))
                        .build());
            }
        }
        // 最近降级事件
        for (var event : failoverEventLog.recent(50)) {
            if (providerName.equals(event.fromProvider()) || providerName.equals(event.toProvider())) {
                decisions.add(RouterMetricsDetail.DecisionLog.builder()
                        .action("FAILOVER")
                        .detail(event.fromProvider() + " → " + event.toProvider() + " (" + event.reason() + ")")
                        .timestamp(formatInstant(event.occurredAt()))
                        .build());
            }
        }

        return RouterMetricsDetail.builder()
                .provider(providerName)
                .healthy(health != null && health.healthy())
                .latencyMs(health != null ? health.latencyMs() : 0)
                .totalCalls(metrics != null ? metrics.totalCalls() : 0)
                .successCalls(metrics != null ? metrics.successCalls() : 0)
                .failureCalls(metrics != null ? metrics.failureCalls() : 0)
                .successRate(metrics != null ? metrics.successRate() : 0)
                .circuitBreakerState(cbState.name())
                .failureRateThreshold(cb.getCircuitBreakerConfig().getFailureRateThreshold())
                .currentFailureRate(cbFailureRate)
                .recentDecisions(decisions)
                .build();
    }

    @Override
    public List<RouterMetricsDetail> allProviderDetails() {
        List<RouterMetricsDetail> details = new ArrayList<>();
        for (ProviderHealth h : healthMonitor.snapshot()) {
            details.add(providerDetail(h.name()));
        }
        return details;
    }

    private ProviderHealth findHealth(List<ProviderHealth> list, String name) {
        return list.stream().filter(h -> h.name().equals(name)).findFirst().orElse(null);
    }

    private String formatInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
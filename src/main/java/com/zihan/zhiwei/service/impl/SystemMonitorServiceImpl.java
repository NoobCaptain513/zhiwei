package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.HealthMonitor;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import com.zihan.zhiwei.pojo.dto.RateLimitStatus;
import com.zihan.zhiwei.pojo.dto.RouterStatus;
import com.zihan.zhiwei.pojo.dto.UsageSummary;
import com.zihan.zhiwei.service.SystemMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 系统监控服务实现。
 */
@Service
@RequiredArgsConstructor
public class SystemMonitorServiceImpl implements SystemMonitorService {

    private final HealthMonitor healthMonitor;
    private final ProviderMetrics providerMetrics;

    @Value("${zhiwei.ai.default-provider:spring-ai-alibaba}")
    private String defaultProvider;

    @Value("${zhiwei.ai.router.failover-chain[0]:spring-ai-alibaba}")
    private String chain0;
    @Value("${zhiwei.ai.router.failover-chain[1]:langchain4j-openai}")
    private String chain1;
    @Value("${zhiwei.ai.router.failover-chain[2]:native-dashscope}")
    private String chain2;

    @Override
    public UsageSummary usageSummary() {
        ProviderMetrics.Snapshot snapshot = providerMetrics.snapshot(defaultProvider);
        long totalCalls = snapshot != null ? snapshot.totalCalls() : 0;
        return UsageSummary.builder()
                .totalRequests(totalCalls)
                .totalTokens(0)
                .estimatedCost(BigDecimal.ZERO)
                .lastUpdatedAt(java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    @Override
    public RouterStatus routerStatus() {
        List<ProviderHealth> health = healthMonitor.snapshot();
        return RouterStatus.builder()
                .defaultProvider(defaultProvider)
                .failoverChain(List.of(chain0, chain1, chain2))
                .providers(health)
                .build();
    }

    @Override
    public RateLimitStatus rateLimitStatus() {
        return RateLimitStatus.builder()
                .enabled(false)
                .limitPerMinute(60)
                .currentCount(0)
                .resetAt(java.time.LocalDateTime.now().plusMinutes(1).format(
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }
}

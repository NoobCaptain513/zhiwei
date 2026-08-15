package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.HealthMonitor;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import com.zihan.zhiwei.mapper.AiUsageLogMapper;
import com.zihan.zhiwei.pojo.dto.RateLimitStatus;
import com.zihan.zhiwei.pojo.dto.RouterStatus;
import com.zihan.zhiwei.pojo.dto.UsageSummary;
import com.zihan.zhiwei.service.SystemMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统监控服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMonitorServiceImpl implements SystemMonitorService {

    private final HealthMonitor healthMonitor;
    private final ProviderMetrics providerMetrics;
    private final AiUsageLogMapper aiUsageLogMapper;

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
        // 1. 全局合计（排除 FAILED）
        Map<String, Object> total = aiUsageLogMapper.selectTotalStats();
        long totalRequests = toLong(total != null ? total.get("requests") : null);
        long totalTokens   = toLong(total != null ? total.get("tokens")   : null);
        BigDecimal totalCost = toBigDecimal(total != null ? total.get("cost") : null);

        // 2. 按 Provider 聚合
        List<Map<String, Object>> providerRows = aiUsageLogMapper.selectStatsByProvider();
        List<UsageSummary.ProviderStat> byProvider = new ArrayList<>();
        for (Map<String, Object> row : providerRows) {
            byProvider.add(UsageSummary.ProviderStat.builder()
                    .provider(String.valueOf(row.get("provider")))
                    .requests(toLong(row.get("requests")))
                    .tokens(toLong(row.get("tokens")))
                    .cost(toBigDecimal(row.get("cost")))
                    .avgLatencyMs(toLong(row.get("avgLatency")))
                    .build());
        }

        // 3. 按天聚合（最近 7 天，含所有 status）
        List<Map<String, Object>> dayRows = aiUsageLogMapper.selectStatsByDay(7);
        List<UsageSummary.DailyStat> byDay = new ArrayList<>();
        for (Map<String, Object> row : dayRows) {
            Object dayVal = row.get("day");
            byDay.add(UsageSummary.DailyStat.builder()
                    .date(dayVal != null ? dayVal.toString() : "")
                    .requests(toLong(row.get("requests")))
                    .tokens(toLong(row.get("tokens")))
                    .cost(toBigDecimal(row.get("cost")))
                    .build());
        }

        return UsageSummary.builder()
                .totalRequests(totalRequests)
                .totalTokens(totalTokens)
                .estimatedCost(totalCost)
                .lastUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .byProvider(byProvider)
                .byDay(byDay)
                .build();
    }

    private static long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros();
        try { return new BigDecimal(val.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
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
        // P2-18 修复：标注为占位符实现，待接入实际限流统计
        log.warn("[SystemMonitor] rateLimitStatus 返回占位符数据，待接入实际限流统计");
        return RateLimitStatus.builder()
                .enabled(false)
                .limitPerMinute(60)
                .currentCount(0)
                .resetAt(java.time.LocalDateTime.now().plusMinutes(1).format(
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }
}

package com.zihan.zhiwei.pojo.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * D16: 用量统计汇总。
 */
@Data
@Builder
public class UsageSummary {
    private long totalRequests;
    private long totalTokens;
    private BigDecimal estimatedCost;
    private String lastUpdatedAt;

    /** 按 Provider 聚合 */
    private List<ProviderStat> byProvider;

    /** 按天聚合（最近 N 天） */
    private List<DailyStat> byDay;

    @Data
    @Builder
    public static class ProviderStat {
        private String provider;
        private long requests;
        private long tokens;
        private BigDecimal cost;
        private long avgLatencyMs;
    }

    @Data
    @Builder
    public static class DailyStat {
        private String date;
        private long requests;
        private long tokens;
        private BigDecimal cost;
    }
}
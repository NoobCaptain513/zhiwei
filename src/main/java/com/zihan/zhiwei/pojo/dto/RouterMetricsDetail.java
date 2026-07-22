package com.zihan.zhiwei.pojo.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 路由监控详情：单 Provider 的实时指标 + 熔断状态 + 决策日志。
 */
@Data
@Builder
public class RouterMetricsDetail {

    private String provider;
    private boolean healthy;
    private long latencyMs;

    /** 总调用 / 成功 / 失败 */
    private long totalCalls;
    private long successCalls;
    private long failureCalls;
    private double successRate;

    /** 熔断器状态：CLOSED / OPEN / HALF_OPEN */
    private String circuitBreakerState;
    /** 熔断器：失败率阈值 */
    private float failureRateThreshold;
    /** 熔断器：当前失败率 */
    private float currentFailureRate;

    /** 最近决策日志 */
    private List<DecisionLog> recentDecisions;

    @Data
    @Builder
    public static class DecisionLog {
        private String action;
        private String detail;
        private String timestamp;
    }
}
package com.zihan.zhiwei.pojo.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

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
}
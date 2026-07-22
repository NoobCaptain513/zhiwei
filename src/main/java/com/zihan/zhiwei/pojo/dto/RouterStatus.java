package com.zihan.zhiwei.pojo.dto;

import com.zihan.zhiwei.ai.provider.dto.ProviderHealth;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D16: 路由器状态（含健康快照 + 降级事件 + 成本权重）。
 */
@Data
@Builder
public class RouterStatus {

    /** 当前首选 Provider */
    private String defaultProvider;

    /** 降级链 */
    private List<String> failoverChain;

    /** 各 Provider 健康状态 */
    private List<ProviderHealth> providers;

    /** 最近降级事件 */
    private List<FailoverEventDto> recentFailovers;

    /** 各 Provider 成本权重 */
    private java.util.Map<String, Double> costWeights;

    @Data
    @Builder
    public static class FailoverEventDto {
        private String from;
        private String to;
        private String reason;
        private String occurredAt;
    }
}
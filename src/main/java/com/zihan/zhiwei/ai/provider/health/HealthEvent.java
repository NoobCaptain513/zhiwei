package com.zihan.zhiwei.ai.provider.health;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * D16: 健康检测事件记录。
 * 每次探测（无论成功/失败）都记一条，供审计和调试。
 */
@Data
@Builder
public class HealthEvent {

    private String provider;
    private boolean healthy;
    private String message;
    private long latencyMs;
    private boolean coldStart;
    private Instant occurredAt;

    public static HealthEvent ok(String provider, long latencyMs, boolean coldStart) {
        return HealthEvent.builder()
                .provider(provider)
                .healthy(true)
                .message("probe ok")
                .latencyMs(latencyMs)
                .coldStart(coldStart)
                .occurredAt(Instant.now())
                .build();
    }

    public static HealthEvent fail(String provider, String message, long latencyMs, boolean coldStart) {
        return HealthEvent.builder()
                .provider(provider)
                .healthy(false)
                .message(message)
                .latencyMs(latencyMs)
                .coldStart(coldStart)
                .occurredAt(Instant.now())
                .build();
    }
}
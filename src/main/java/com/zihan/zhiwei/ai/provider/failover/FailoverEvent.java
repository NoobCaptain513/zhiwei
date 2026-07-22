package com.zihan.zhiwei.ai.provider.failover;

import java.time.Instant;

/** 一次降级切换事件，便于排查与后续写入 usage/监控 */
public record FailoverEvent(
        String fromProvider,
        String toProvider,
        String reason,
        Instant occurredAt
) {
    public static FailoverEvent of(String from, String to, String reason) {
        return new FailoverEvent(from, to, reason, Instant.now());
    }
}
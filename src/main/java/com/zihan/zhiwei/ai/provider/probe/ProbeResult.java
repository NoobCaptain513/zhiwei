package com.zihan.zhiwei.ai.provider.probe;

import java.time.Instant;

public record ProbeResult(
        String provider,
        boolean success,
        long latencyMs,
        String error,
        Instant probedAt
) {
    public static ProbeResult ok(String provider, long latencyMs) {
        return new ProbeResult(provider, true, latencyMs, null, Instant.now());
    }

    public static ProbeResult fail(String provider, long latencyMs, String error) {
        return new ProbeResult(provider, false, latencyMs, error, Instant.now());
    }
}

package com.zihan.zhiwei.ai.provider.failover;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;

import java.util.List;

public record FailoverResult(
        ProviderChatResponse response,
        String primaryProvider,
        String actualProvider,
        boolean degraded,
        long latencyMs,
        List<FailoverEvent> events
) {}
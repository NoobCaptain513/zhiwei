package com.zihan.zhiwei.pojo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * D9: 最近一次用量明细，对应 GET /api/ai/usage/recent
 */
public record UsageRecentItem(
        Long id,
        Long conversationId,
        Long messageId,
        String provider,
        String model,
        String mode,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal cost,
        Long latencyMs,
        String status,
        LocalDateTime createTime
) {}
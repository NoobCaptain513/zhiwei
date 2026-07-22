package com.zihan.zhiwei.pojo.dto;

import lombok.Builder;
import lombok.Data;

/**
 * D16: 限流状态（第一周占位）。
 */
@Data
@Builder
public class RateLimitStatus {
    private boolean enabled;
    private int limitPerMinute;
    private int currentCount;
    private String resetAt;
}
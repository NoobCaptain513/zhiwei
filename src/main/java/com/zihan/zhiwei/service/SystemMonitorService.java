package com.zihan.zhiwei.service;

import com.zihan.zhiwei.pojo.dto.RateLimitStatus;
import com.zihan.zhiwei.pojo.dto.RouterStatus;
import com.zihan.zhiwei.pojo.dto.UsageSummary;

/**
 * 系统监控服务：用量、路由状态、限流状态。
 */
public interface SystemMonitorService {

    UsageSummary usageSummary();

    RouterStatus routerStatus();

    RateLimitStatus rateLimitStatus();
}

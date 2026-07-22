package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.RateLimitStatus;
import com.zihan.zhiwei.pojo.dto.RouterMetricsDetail;
import com.zihan.zhiwei.pojo.dto.RouterStatus;
import com.zihan.zhiwei.pojo.dto.UsageSummary;
import com.zihan.zhiwei.service.RouterMonitorService;
import com.zihan.zhiwei.service.SystemMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/system")
@Tag(name = "系统监控")
@RequiredArgsConstructor
public class SystemController {

    private final SystemMonitorService systemMonitorService;
    private final RouterMonitorService routerMonitorService;
    private final FailoverEventLog failoverEventLog;

    @GetMapping("/usage")
    @Operation(summary = "查询用量统计")
    public Result<UsageSummary> usage() {
        return Result.ok(systemMonitorService.usageSummary());
    }

    @GetMapping("/router/status")
    @Operation(summary = "路由状态（含降级事件 + 成本权重）")
    public Result<RouterStatus> routerStatus() {
        return Result.ok(routerMonitorService.fullStatus());
    }

    @GetMapping("/router/detail/{provider}")
    @Operation(summary = "单 Provider 监控详情（指标 + 熔断 + 决策日志）")
    public Result<RouterMetricsDetail> providerDetail(@PathVariable String provider) {
        return Result.ok(routerMonitorService.providerDetail(provider));
    }

    @GetMapping("/router/details")
    @Operation(summary = "所有 Provider 监控详情")
    public Result<List<RouterMetricsDetail>> allProviderDetails() {
        return Result.ok(routerMonitorService.allProviderDetails());
    }

    @GetMapping("/router/events")
    @Operation(summary = "最近降级事件日志")
    public Result<List<Map<String, Object>>> failoverEvents(
            @RequestParam(defaultValue = "20") int limit) {
        var events = failoverEventLog.recent(limit);
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : events) {
            list.add(Map.of(
                    "from", e.fromProvider(),
                    "to", e.toProvider(),
                    "reason", e.reason(),
                    "occurredAt", e.occurredAt().toString()));
        }
        return Result.ok(list);
    }

    @GetMapping("/ratelimit")
    @Operation(summary = "查询限流状态")
    public Result<RateLimitStatus> rateLimit() {
        return Result.ok(systemMonitorService.rateLimitStatus());
    }
}
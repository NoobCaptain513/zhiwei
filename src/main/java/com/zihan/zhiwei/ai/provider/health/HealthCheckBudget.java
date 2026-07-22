package com.zihan.zhiwei.ai.provider.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D16: 冷启动预算配置。
 * 应用刚启动时用更短的间隔探测 Provider，快速建立健康视图。
 *
 * 冷启动三轮：
 *   T+5s  → 首轮探测（预算 5s/Provider）
 *   T+10s → 二轮补充（预算 10s）
 *   T+15s → 三轮确认（预算 15s）
 * 之后切到正常周期（默认 30s）
 */
@Data
@Component
@ConfigurationProperties(prefix = "zhiwei.ai.health.budget")
public class HealthCheckBudget {

    /** 冷启动首轮延迟（ms） */
    private long firstProbeDelayMs = 5000;

    /** 冷启动二轮延迟（ms） */
    private long secondProbeDelayMs = 10000;

    /** 冷启动三轮延迟（ms） */
    private long thirdProbeDelayMs = 15000;

    /** 冷启动期间单 Provider 探测超时（ms） */
    private long probeTimeoutMs = 5000;

    /** 是否启用冷启动预算 */
    private boolean enabled = true;
}
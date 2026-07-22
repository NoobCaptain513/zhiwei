package com.zihan.zhiwei.ai.provider.dto;

import com.zihan.zhiwei.pojo.dto.RouterStatus;

/**
 * 单个 AI Provider 的健康状态。
 * <p>
 * 作为 {@link RouterStatus} 的子项，描述某个模型提供方的可用性与响应性能。
 * 后续由 {@code HealthMonitor} 通过心跳探测填充。
 */
public record ProviderHealth(
    /** Provider 标识，如 spring-ai-alibaba、langchain4j-openai */
    String name,
    /** 是否健康（心跳成功且未触发熔断） */
    boolean healthy,
    /** 最近一次探测的响应延迟（毫秒） */
    long latencyMs,
    /** 状态说明，如 ok、timeout、circuit-open */
    String message
) {}
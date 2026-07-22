package com.zihan.zhiwei.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用用量日志实体（D9）。
 */
@Data
@TableName("ai_usage_log")
public class AiUsageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long messageId;

    /** Provider 名称，如 spring-ai-alibaba */
    private String provider;

    /** 模型名称，如 qwen-plus */
    private String model;

    /** 调用模式：chat / agent / stream */
    private String mode;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 本次调用费用（元） */
    private BigDecimal cost;

    /** 本次调用耗时（毫秒） */
    private Long latencyMs;

    /** 调用状态：SUCCESS / FAILED / DEGRADED */
    private String status;

    private LocalDateTime createTime;
}
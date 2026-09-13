package com.zihan.zhiwei.ai.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 单个工具调用的结果。
 */
@Data
@Builder
public class ToolCallResult {
    /** 工具名 */
    private String toolName;
    /** 是否成功 */
    private boolean success;
    /** 工具返回的数据（JSON 或文本） */
    private String data;
    /** 失败时的错误信息 */
    private String error;
    /** 可靠执行层的终态；旧调用方未设置时允许为 null */
    private ToolCallStatus status;
    /** 实际尝试次数 */
    private int attempts;
    /** 工具调用耗时 */
    private long latencyMs;
    /** 有副作用工具的审批凭证 */
    private String approvalId;
}
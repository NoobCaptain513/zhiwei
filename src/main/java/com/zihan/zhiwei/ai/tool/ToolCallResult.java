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
}
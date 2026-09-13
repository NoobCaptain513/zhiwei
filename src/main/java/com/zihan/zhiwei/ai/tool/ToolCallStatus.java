package com.zihan.zhiwei.ai.tool;

public enum ToolCallStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    APPROVAL_REQUIRED,
    APPROVAL_REJECTED,
    IN_PROGRESS
}

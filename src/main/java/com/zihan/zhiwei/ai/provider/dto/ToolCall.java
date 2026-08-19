package com.zihan.zhiwei.ai.provider.dto;

/** A complete model-requested tool call. */
public record ToolCall(
        String id,
        String name,
        String arguments
) {}

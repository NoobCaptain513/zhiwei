package com.zihan.zhiwei.ai.provider.dto;

import java.util.Map;

/** Provider-neutral function tool schema. */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {}

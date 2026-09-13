package com.zihan.zhiwei.ai.tool;

import java.util.Map;

public record ToolInvocation(String toolName, Map<String, Object> params) {
    public ToolInvocation {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}

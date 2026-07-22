package com.zihan.zhiwei.ai.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 工具定义（tools/list 返回格式）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public static McpToolDefinition of(String name, String description, Map<String, Object> inputSchema) {
        return McpToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }
}
package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.mcp.McpToolService;
import com.zihan.zhiwei.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
@Tag(name = "MCP Health")
@RequiredArgsConstructor
public class McpHealthController {

    private final McpToolService mcpToolService;

    @GetMapping
    @Operation(summary = "MCP Server 健康检查")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "server", "zhiwei-mcp-server",
                "version", "0.2.0",
                "protocol", "2025-03-26",
                "tools", mcpToolService.listTools().size()
        ));
    }

    @GetMapping("/ready")
    @Operation(summary = "就绪探针")
    public Result<Map<String, Object>> ready() {
        return Result.ok(Map.of(
                "ready", true,
                "tools", mcpToolService.listTools().size()
        ));
    }
}

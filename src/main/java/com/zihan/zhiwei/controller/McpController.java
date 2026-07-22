package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.mcp.McpJsonRpcService;
import com.zihan.zhiwei.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP Server 入口。
 * POST /api/mcp — JSON-RPC 2.0
 * 支持方法：initialize / tools/list / tools/call
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP Server")
@RequiredArgsConstructor
public class McpController {

    private final McpJsonRpcService mcpJsonRpcService;

    @PostMapping
    @Operation(summary = "MCP JSON-RPC 入口")
    public Result<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = mcpJsonRpcService.handle(request);
        if (response == null) {
            // notifications 不需要响应
            return Result.ok(null);
        }
        return Result.ok(response);
    }
}
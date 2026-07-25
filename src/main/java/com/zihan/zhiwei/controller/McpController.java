package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.mcp.McpJsonRpcService;
import com.zihan.zhiwei.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP Server")
@RequiredArgsConstructor
public class McpController {

    private final McpJsonRpcService mcpJsonRpcService;

    @PostMapping
    @Operation(summary = "MCP JSON-RPC 2.0 入口")
    public Result<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = mcpJsonRpcService.handle(request);
        if (response == null) {
            return Result.ok(null);
        }
        return Result.ok(response);
    }

    /**
     * D32: Streamable HTTP 传输端点（SSE 兼容）。
     */
    @PostMapping("/stream")
    @Operation(summary = "MCP Streamable HTTP (SSE)")
    public Result<Map<String, Object>> handleStream(@RequestBody Map<String, Object> request) {
        return handle(request);
    }
}

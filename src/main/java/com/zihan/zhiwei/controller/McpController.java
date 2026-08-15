package com.zihan.zhiwei.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.mcp.McpJsonRpcService;
import com.zihan.zhiwei.ai.stream.AiStreamAdvice;
import com.zihan.zhiwei.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP Server")
@RequiredArgsConstructor
public class McpController {

    private final McpJsonRpcService mcpJsonRpcService;
    private final AiStreamAdvice sse;
    private final ObjectMapper objectMapper;

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
     * D32: Streamable HTTP 传输端点（真正的 SSE 流式实现）。
     * - 普通方法（initialize / tools/list 等）：同步执行，包一帧推出去
     * - tools/call：支持执行前推进度通知，最后推结果帧
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "MCP Streamable HTTP (SSE)")
    public SseEmitter handleStream(@RequestBody Map<String, Object> request) {
        return sse.execute(emitter -> {
            String method = (String) request.get("method");

            if ("tools/call".equals(method)) {
                // tools/call：流式版本，支持进度推送
                mcpJsonRpcService.handleStream(request, event -> {
                    try {
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
                    } catch (Exception e) {
                        log.warn("[MCP/Stream] send event failed: {}", e.getMessage());
                    }
                });
            } else {
                // 其他方法：同步处理，包一帧推出去
                Map<String, Object> response = mcpJsonRpcService.handle(request);
                if (response != null) {
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response)));
                }
            }
        });
    }
}

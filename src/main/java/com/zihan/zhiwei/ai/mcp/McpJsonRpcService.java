package com.zihan.zhiwei.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 2.0 协议处理。
 * 支持方法：initialize / tools/list / tools/call
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpJsonRpcService {

    private final McpToolService mcpToolService;
    private final ObjectMapper objectMapper;

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "zhiwei-mcp-server";
    private static final String SERVER_VERSION = "0.1.0";

    /**
     * 处理 JSON-RPC 请求，返回 JSON-RPC 响应。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        String jsonrpc = (String) request.getOrDefault("jsonrpc", "2.0");
        Object id = request.get("id");
        String method = (String) request.get("method");
        Object params = request.get("params");

        log.info("[MCP] method={} id={}", method, id);

        try {
            return switch (method) {
                case "initialize"      -> handleInitialize(id);
                case "tools/list"      -> handleToolsList(id);
                case "tools/call"      -> handleToolsCall(id, params);
                case "notifications/initialized" -> null; // 通知，无需响应
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.warn("[MCP] method={} error: {}", method, e.getMessage());
            return errorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ========== initialize ==========

    private Map<String, Object> handleInitialize(Object id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false)
        ));
        result.put("serverInfo", Map.of(
                "name", SERVER_NAME,
                "version", SERVER_VERSION
        ));
        return successResponse(id, result);
    }

    // ========== tools/list ==========

    private Map<String, Object> handleToolsList(Object id) {
        List<McpToolDefinition> tools = mcpToolService.listTools();
        return successResponse(id, Map.of("tools", tools));
    }

    // ========== tools/call ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Object id, Object params) {
        if (!(params instanceof Map)) {
            return errorResponse(id, -32602, "Invalid params");
        }
        Map<String, Object> paramsMap = (Map<String, Object>) params;
        String toolName = (String) paramsMap.get("name");
        Map<String, Object> arguments = (Map<String, Object>) paramsMap.getOrDefault("arguments", Map.of());

        if (toolName == null || toolName.isBlank()) {
            return errorResponse(id, -32602, "Missing tool name");
        }

        McpToolService.McpToolResult result = mcpToolService.call(toolName, arguments);
        return successResponse(id, result);
    }

    // ========== 响应构建 ==========

    private Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        return resp;
    }
}
package com.zihan.zhiwei.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D32: MCP JSON-RPC 2.0 协议处理。
 * 支持：initialize / tools/list / tools/call / prompts/list / resources/list / ping
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpJsonRpcService {

    private final McpToolService mcpToolService;
    private final ObjectMapper objectMapper;

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SERVER_NAME = "zhiwei-mcp-server";
    private static final String SERVER_VERSION = "0.2.0";

    /**
     * 处理 JSON-RPC 请求，返回 JSON-RPC 响应（null 表示 notification）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        String jsonrpc = (String) request.get("jsonrpc");
        Object id = request.get("id");
        String method = (String) request.get("method");
        Object params = request.get("params");

        if (!"2.0".equals(jsonrpc)) {
            return errorResponse(id, -32600, "Invalid Request: jsonrpc must be '2.0'");
        }

        log.info("[MCP] method={} id={}", method, id);

        try {
            return switch (method) {
                case "initialize"                  -> handleInitialize(id);
                case "tools/list"                  -> handleToolsList(id);
                case "tools/call"                  -> handleToolsCall(id, params);
                case "prompts/list"                -> handlePromptsList(id);
                case "resources/list"              -> handleResourcesList(id);
                case "ping"                        -> successResponse(id, Map.of());
                case "notifications/initialized"   -> null;
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.error("[MCP] method={} error", method, e);
            return errorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ==================== initialize ====================

    private Map<String, Object> handleInitialize(Object id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false),
                "prompts", Map.of("listChanged", false),
                "resources", Map.of("subscribe", false, "listChanged", false)
        ));
        result.put("serverInfo", Map.of(
                "name", SERVER_NAME,
                "version", SERVER_VERSION
        ));
        return successResponse(id, result);
    }

    // ==================== tools/list ====================

    private Map<String, Object> handleToolsList(Object id) {
        List<McpToolDefinition> tools = mcpToolService.listTools();
        return successResponse(id, Map.of("tools", tools));
    }

    // ==================== tools/call ====================

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

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", result.getContent());
        if (result.isError()) {
            r.put("isError", true);
        }
        return successResponse(id, r);
    }

    // ==================== D32: prompts/list ====================

    private Map<String, Object> handlePromptsList(Object id) {
        List<Map<String, Object>> prompts = new ArrayList<>();

        prompts.add(Map.of(
                "name", "diagnose",
                "description", "运维故障诊断提示词",
                "arguments", List.of(
                        Map.of("name", "service", "description", "服务名称", "required", true),
                        Map.of("name", "incident", "description", "故障描述", "required", true)
                )
        ));
        prompts.add(Map.of(
                "name", "summarize_metric",
                "description", "指标摘要提示词",
                "arguments", List.of(
                        Map.of("name", "metrics_json", "description", "指标 JSON", "required", true)
                )
        ));

        return successResponse(id, Map.of("prompts", prompts));
    }

    // ==================== D32: resources/list ====================

    private Map<String, Object> handleResourcesList(Object id) {
        List<Map<String, String>> resources = new ArrayList<>();

        resources.add(Map.of(
                "uri", "zhiwei://knowledge/latest",
                "name", "最新运维知识库",
                "mimeType", "application/json",
                "description", "最近更新的 20 条运维知识条目"
        ));
        resources.add(Map.of(
                "uri", "zhiwei://server/list",
                "name", "服务器清单",
                "mimeType", "application/json",
                "description", "当前纳管的服务器列表及状态"
        ));

        return successResponse(id, Map.of("resources", resources));
    }

    // ==================== 响应构建 ====================

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

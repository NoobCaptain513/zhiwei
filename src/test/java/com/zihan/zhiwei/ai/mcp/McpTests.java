package com.zihan.zhiwei.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MCP Server 测试")
class McpTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ──────────────────────────────────────────
    // McpToolDefinition 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("McpToolDefinition")
    class ToolDefinitionTests {

        @Test
        @DisplayName("of() 工厂方法 → 字段完整")
        void shouldBuildDefinition() {
            Map<String, Object> schema = Map.of("type", "object",
                    "properties", Map.of("query", Map.of("type", "string")));

            McpToolDefinition def = McpToolDefinition.of("rag_search", "搜索知识库", schema);

            assertThat(def.getName()).isEqualTo("rag_search");
            assertThat(def.getDescription()).isEqualTo("搜索知识库");
            assertThat(def.getInputSchema()).containsEntry("type", "object");
        }

        @Test
        @DisplayName("Builder 构建 → 所有字段可设")
        void shouldBuildWithBuilder() {
            McpToolDefinition def = McpToolDefinition.builder()
                    .name("test").description("desc")
                    .inputSchema(Map.of("type", "object"))
                    .build();

            assertThat(def.getName()).isEqualTo("test");
            assertThat(def.getDescription()).isEqualTo("desc");
        }

        @Test
        @DisplayName("无参构造 + setter")
        void shouldWorkWithSetters() {
            McpToolDefinition def = new McpToolDefinition();
            def.setName("t1");
            def.setDescription("d1");

            assertThat(def.getName()).isEqualTo("t1");
        }
    }

    // ──────────────────────────────────────────
    // McpJsonRpcService 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("McpJsonRpcService JSON-RPC 协议")
    class JsonRpcServiceTests {

        @Mock private McpToolService mcpToolService;

        private McpJsonRpcService jsonRpcService;

        @BeforeEach
        void setUp() {
            jsonRpcService = new McpJsonRpcService(mcpToolService, objectMapper);
        }

        @Test
        @DisplayName("initialize → 返回 protocolVersion + serverInfo + capabilities")
        void shouldHandleInitialize() {
            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));

            assertThat(response.get("jsonrpc")).isEqualTo("2.0");
            assertThat(response.get("id")).isEqualTo(1);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            assertThat(result.get("protocolVersion")).isEqualTo("2024-11-05");
        }

        @Test
        @DisplayName("tools/list → 返回工具列表")
        void shouldHandleToolsList() {
            when(mcpToolService.listTools()).thenReturn(List.of(
                    McpToolDefinition.of("t1", "desc1", Map.of())));

            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 2, "method", "tools/list"));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            assertThat(result.get("tools")).asList().hasSize(1);
        }

        @Test
        @DisplayName("tools/call → 成功调用")
        void shouldHandleToolsCall() {
            McpToolService.McpToolResult toolResult = McpToolService.McpToolResult.builder()
                    .content(List.of(Map.of("type", "text", "text", "ok")))
                    .isError(false)
                    .build();
            when(mcpToolService.call(eq("queryServerStatus"), anyMap())).thenReturn(toolResult);

            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", Map.of(
                            "name", "queryServerStatus",
                            "arguments", Map.of("hostname", "redis-01"))));

            assertThat(response.get("jsonrpc")).isEqualTo("2.0");
            McpToolService.McpToolResult result = (McpToolService.McpToolResult) response.get("result");
            assertThat(result.isError()).isFalse();
        }

        @Test
        @DisplayName("tools/call → 缺少 toolName")
        void shouldReturnErrorForMissingToolName() {
            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                    "params", Map.of()));

            assertThat(response.get("error")).isNotNull();
        }

        @Test
        @DisplayName("未知 method → -32601")
        void shouldReturnMethodNotFound() {
            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 5, "method", "unknown/method"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            assertThat(error.get("code")).isEqualTo(-32601);
            assertThat(error.get("message")).toString().contains("Method not found");
        }

        @Test
        @DisplayName("notifications/initialized → 返回 null（不响应）")
        void shouldReturnNullForNotification() {
            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "method", "notifications/initialized"));

            assertThat(response).isNull();
        }

        @Test
        @DisplayName("异常 → -32603 Internal error")
        void shouldReturnInternalError() {
            when(mcpToolService.listTools()).thenThrow(new RuntimeException("DB down"));

            Map<String, Object> response = jsonRpcService.handle(Map.of(
                    "jsonrpc", "2.0", "id", 6, "method", "tools/list"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            assertThat(error.get("code")).isEqualTo(-32603);
            assertThat(error.get("message")).toString().contains("DB down");
        }
    }

    // ──────────────────────────────────────────
    // McpToolService 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("McpToolService 工具注册 + 执行")
    class ToolServiceTests {

        @Mock private OpsAgentToolService opsAgentToolService;
        @Mock private AiRagService aiRagService;

        private McpToolService toolService;

        @BeforeEach
        void setUp() {
            toolService = new McpToolService(opsAgentToolService, aiRagService);
        }

        @Test
        @DisplayName("listTools → 返回 6 个工具（5 运维 + 1 RAG）")
        void shouldListSixTools() {
            when(opsAgentToolService.toolDefinitions()).thenReturn(List.of(
                    Map.of("name", "queryServerStatus", "description", "desc",
                            "parameters", Map.of("type", "object")),
                    Map.of("name", "searchLogs", "description", "desc",
                            "parameters", Map.of("type", "object")),
                    Map.of("name", "queryDeployHistory", "description", "desc",
                            "parameters", Map.of("type", "object")),
                    Map.of("name", "createTicket", "description", "desc",
                            "parameters", Map.of("type", "object")),
                    Map.of("name", "queryMetrics", "description", "desc",
                            "parameters", Map.of("type", "object"))));

            List<McpToolDefinition> tools = toolService.listTools();

            assertThat(tools).hasSize(6);
            assertThat(tools.get(5).getName()).isEqualTo("rag_search");
        }

        @Test
        @DisplayName("call 运维工具 → 委托 OpsAgentToolService")
        void shouldDelegateOpsTool() {
            ToolCallResult mockResult = ToolCallResult.builder()
                    .toolName("queryServerStatus").success(true)
                    .data("{\"cpu\":\"23%\"}")
                    .build();
            when(opsAgentToolService.execute(eq("queryServerStatus"), anyMap()))
                    .thenReturn(mockResult);

            McpToolService.McpToolResult result = toolService.call("queryServerStatus",
                    Map.of("hostname", "redis-01"));

            assertThat(result.isError()).isFalse();
            assertThat(result.getContent()).isNotEmpty();
        }

        @Test
        @DisplayName("call 运维工具失败 → isError=true")
        void shouldReturnErrorForFailedOpsTool() {
            ToolCallResult failResult = ToolCallResult.builder()
                    .toolName("unknown").success(false).error("未知工具")
                    .build();
            when(opsAgentToolService.execute(eq("unknown"), anyMap()))
                    .thenReturn(failResult);

            McpToolService.McpToolResult result = toolService.call("unknown", Map.of());

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("call rag_search → 返回检索结果")
        void shouldCallRagSearch() {
            RagHit hit = new RagHit(
                    new KnowledgeChunk(1L, 100L, "doc-redis", "Redis 指南", "Redis 集群...", 50, null),
                    0.92, 0.08, 0.85);
            when(aiRagService.search(eq("Redis 集群"), anyInt(), anyInt()))
                    .thenReturn(List.of(hit));

            McpToolService.McpToolResult result = toolService.call("rag_search",
                    Map.of("query", "Redis 集群", "topK", 5));

            assertThat(result.isError()).isFalse();
            assertThat(result.getContent().get(0).get("text")).toString()
                    .contains("Redis 指南");
            assertThat(result.getContent().get(0).get("text")).toString()
                    .contains("0.8500");
        }

        @Test
        @DisplayName("call rag_search → 空 query")
        void shouldReturnErrorForEmptyRagQuery() {
            McpToolService.McpToolResult result = toolService.call("rag_search",
                    Map.of("query", ""));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent().get(0).get("text")).isEqualTo("query 不能为空");
        }

        @Test
        @DisplayName("call rag_search → 无结果")
        void shouldReturnNoResults() {
            when(aiRagService.search(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            McpToolService.McpToolResult result = toolService.call("rag_search",
                    Map.of("query", "nothing"));

            assertThat(result.isError()).isFalse();
            assertThat(result.getContent().get(0).get("text")).isEqualTo("未找到相关知识。");
        }

        @Test
        @DisplayName("call 异常 → isError=true")
        void shouldCatchException() {
            when(opsAgentToolService.execute(eq("badTool"), anyMap()))
                    .thenThrow(new RuntimeException("BOOM"));

            McpToolService.McpToolResult result = toolService.call("badTool", Map.of());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent().get(0).get("text")).toString().contains("BOOM");
        }
    }
}

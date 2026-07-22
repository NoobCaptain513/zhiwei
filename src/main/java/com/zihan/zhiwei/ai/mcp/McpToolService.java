package com.zihan.zhiwei.ai.mcp;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 工具注册中心 + 执行器。
 * 5 个运维工具（委托 OpsAgentToolService）+ 1 个 RAG 搜索工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final OpsAgentToolService opsAgentToolService;
    private final AiRagService aiRagService;

    /** 全部工具定义 */
    public List<McpToolDefinition> listTools() {
        List<McpToolDefinition> tools = new ArrayList<>();

        // 1~5：运维工具（从 OpsAgentToolService 复制定义）
        tools.addAll(opsToolDefinitions());

        // 6：RAG 搜索
        tools.add(ragSearchDefinition());

        return tools;
    }

    /** 按名称执行工具 */
    public McpToolResult call(String toolName, Map<String, Object> arguments) {
        log.info("[MCP] call tool={} args={}", toolName, arguments);

        try {
            if ("rag_search".equals(toolName)) {
                return callRagSearch(arguments);
            }
            // 运维工具委托
            ToolCallResult result = opsAgentToolService.execute(toolName, arguments);
            return McpToolResult.builder()
                    .content(List.of(mcpContent(result.getData() != null ? result.getData() : result.getError())))
                    .isError(!result.isSuccess())
                    .build();
        } catch (Exception e) {
            log.warn("[MCP] tool={} failed: {}", toolName, e.getMessage());
            return McpToolResult.builder()
                    .content(List.of(mcpContent("工具执行失败: " + e.getMessage())))
                    .isError(true)
                    .build();
        }
    }

    // ========== 运维工具定义（复用 OpsAgentToolService）==========

    private List<McpToolDefinition> opsToolDefinitions() {
        return opsAgentToolService.toolDefinitions().stream()
                .map(def -> McpToolDefinition.of(
                        (String) def.get("name"),
                        (String) def.get("description"),
                        (Map<String, Object>) def.get("parameters")))
                .collect(Collectors.toList());
    }

    // ========== RAG 搜索工具 ==========

    private McpToolDefinition ragSearchDefinition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索查询文本"),
                        "topK", Map.of("type", "integer", "description", "返回条数", "default", 5),
                        "candidateK", Map.of("type", "integer", "description", "候选召回数", "default", 20)
                ),
                "required", List.of("query")
        );
        return McpToolDefinition.of("rag_search", "搜索智维知识库（向量 + 字面混合检索）", schema);
    }

    private McpToolResult callRagSearch(Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        if (query.isBlank()) {
            return McpToolResult.builder()
                    .content(List.of(mcpContent("query 不能为空")))
                    .isError(true)
                    .build();
        }

        int topK = args.get("topK") instanceof Number n ? n.intValue() : 5;
        int candidateK = args.get("candidateK") instanceof Number n ? n.intValue() : 20;

        List<RagHit> hits = aiRagService.search(query, topK, candidateK);

        // 格式化为可读文本
        if (hits.isEmpty()) {
            return McpToolResult.builder()
                    .content(List.of(mcpContent("未找到相关知识。")))
                    .isError(false)
                    .build();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 条结果：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RagHit hit = hits.get(i);
            sb.append(String.format("[%d] %s (综合分: %.4f)\n", i + 1,
                    hit.chunk().title() != null ? hit.chunk().title() : "无标题",
                    hit.finalScore()));
            sb.append("    ").append(hit.chunk().content()).append("\n\n");
        }

        return McpToolResult.builder()
                .content(List.of(mcpContent(sb.toString())))
                .isError(false)
                .build();
    }

    private Map<String, String> mcpContent(String text) {
        return Map.of("type", "text", "text", text);
    }

    // ========== 内部 DTO ==========

    @Data
    @Builder
    public static class McpToolResult {
        private List<Map<String, String>> content;
        private boolean isError;
    }
}
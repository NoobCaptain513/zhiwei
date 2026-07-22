package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * D13: 结构化卡片组装器。
 * 从工具返回的原始 JSON 中提取字段，组装为统一的 Card，
 * 按 sourceId 去重，按类型分组。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultCardAssembler {

    private final ObjectMapper objectMapper;

    /**
     * 从工具结果列表生成卡片列表（含合并去重）
     */
    public List<AgentReply.Card> assemble(List<ToolCallResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }

        Map<String, AgentReply.Card> dedup = new LinkedHashMap<>();

        for (ToolCallResult result : toolResults) {
            if (!result.isSuccess() || result.getData() == null) continue;

            try {
                JsonNode root = objectMapper.readTree(result.getData());
                AgentReply.Card card = buildCard(result.getToolName(), root);
                if (card != null) {
                    String key = card.getType() + ":" + card.getSourceId();
                    if (dedup.containsKey(key)) {
                        // 合并：只追加新字段，不覆盖已有非空字段
                        AgentReply.Card existing = dedup.get(key);
                        if (card.getFields() != null) {
                            card.getFields().forEach((k, v) -> {
                                String existingVal = existing.getFields().get(k);
                                if (existingVal == null || "-".equals(existingVal)) {
                                    existing.getFields().put(k, v);
                                }
                            });
                        }
                    } else {
                        dedup.put(key, card);
                    }
                }
            } catch (Exception e) {
                log.debug("[Card] parse failed tool={}, err={}", result.getToolName(), e.getMessage());
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 合并多来源的卡片（工具 + RAG），去重
     */
    public List<AgentReply.Card> merge(List<AgentReply.Card> fromTools, List<AgentReply.Card> fromRag) {
        Map<String, AgentReply.Card> dedup = new LinkedHashMap<>();
        if (fromTools != null) {
            for (AgentReply.Card c : fromTools) {
                dedup.put(c.getType() + ":" + c.getSourceId(), c);
            }
        }
        if (fromRag != null) {
            for (AgentReply.Card c : fromRag) {
                String key = c.getType() + ":" + c.getSourceId();
                if (!dedup.containsKey(key)) {
                    dedup.put(key, c);
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /** 根据工具名 + JSON 构建卡片 */
    private AgentReply.Card buildCard(String toolName, JsonNode root) {
        return switch (toolName) {
            case "queryServerStatus" -> buildServerCard(root);
            case "createTicket"      -> buildTicketCard(root);
            case "queryMetrics"      -> buildMetricCard(root);
            case "searchLogs"        -> buildLogCard(root);
            case "queryDeployHistory"-> buildDeployCard(root);
            default -> null;
        };
    }

    private AgentReply.Card buildServerCard(JsonNode root) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("状态", str(root, "status"));
        fields.put("CPU", str(root, "cpu"));
        fields.put("内存", str(root, "memory"));
        fields.put("磁盘", str(root, "disk"));
        fields.put("运行时间", str(root, "uptime"));
        return AgentReply.Card.builder()
                .type("server")
                .title("服务器状态 - " + str(root, "hostname"))
                .sourceId("server:" + str(root, "hostname"))
                .fields(fields).build();
    }

    private AgentReply.Card buildTicketCard(JsonNode root) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("工单号", str(root, "ticketId"));
        fields.put("标题", str(root, "title"));
        fields.put("优先级", str(root, "priority"));
        fields.put("指派", str(root, "assignee"));
        fields.put("状态", str(root, "status"));
        return AgentReply.Card.builder()
                .type("ticket")
                .title("工单 - " + str(root, "title"))
                .sourceId("ticket:" + str(root, "ticketId"))
                .fields(fields).build();
    }

    private AgentReply.Card buildMetricCard(JsonNode root) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("服务", str(root, "service"));
        fields.put("指标", str(root, "metric"));
        fields.put("当前值", str(root, "current") + " " + str(root, "unit"));
        fields.put("时间范围", str(root, "duration"));
        return AgentReply.Card.builder()
                .type("metric")
                .title("监控指标 - " + str(root, "metric"))
                .sourceId("metric:" + str(root, "service") + ":" + str(root, "metric"))
                .fields(fields).build();
    }

    private AgentReply.Card buildLogCard(JsonNode root) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("服务", str(root, "service"));
        fields.put("关键字", str(root, "keyword"));
        fields.put("命中数", str(root, "totalHits"));
        fields.put("时间范围", str(root, "timeRange"));
        JsonNode samples = root.path("sampleLogs");
        if (samples.isArray() && samples.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : samples) {
                sb.append("  ").append(line.asText()).append("\n");
            }
            fields.put("示例日志", sb.toString().trim());
        }
        return AgentReply.Card.builder()
                .type("log")
                .title("日志查询 - " + str(root, "service"))
                .sourceId("log:" + str(root, "service") + ":" + str(root, "keyword"))
                .fields(fields).build();
    }

    private AgentReply.Card buildDeployCard(JsonNode root) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("服务", str(root, "service"));
        JsonNode deploys = root.path("deploys");
        if (deploys.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode d : deploys) {
                sb.append(String.format("  %s | %s | %s | %s\n",
                        str(d, "version"), str(d, "time"), str(d, "author"), str(d, "status")));
            }
            fields.put("部署记录", sb.toString().trim());
        }
        return AgentReply.Card.builder()
                .type("deploy")
                .title("部署记录 - " + str(root, "service"))
                .sourceId("deploy:" + str(root, "service"))
                .fields(fields).build();
    }

    private String str(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "-" : v.asText();
    }
}
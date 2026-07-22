package com.zihan.zhiwei.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D12: 5 个运维工具集。
 * 在真实项目中，每个方法对接 Prometheus / ELK / Jenkins / 工单系统等。
 * 当前为 Mock 实现，展示工具调用的完整链路和返回结构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAgentToolService {

    private final ObjectMapper objectMapper;

    /**
     * 工具列表定义（供 LLM Function Calling / MCP tools/list）
     */
    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of(
                        "name", "queryServerStatus",
                        "description", "查询指定服务器的运行状态（CPU / 内存 / 磁盘 / 进程）",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "hostname", Map.of("type", "string", "description", "服务器主机名或 IP")
                                ),
                                "required", List.of("hostname")
                        )
                ),
                Map.of(
                        "name", "searchLogs",
                        "description", "搜索指定服务的最近错误日志",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string", "description", "服务名"),
                                        "keyword", Map.of("type", "string", "description", "搜索关键字"),
                                        "minutes", Map.of("type", "integer", "description", "最近多少分钟")
                                ),
                                "required", List.of("service")
                        )
                ),
                Map.of(
                        "name", "queryDeployHistory",
                        "description", "查询指定服务的最近部署记录",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string", "description", "服务名")
                                ),
                                "required", List.of("service")
                        )
                ),
                Map.of(
                        "name", "createTicket",
                        "description", "创建一个运维工单",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "title", Map.of("type", "string", "description", "工单标题"),
                                        "description", Map.of("type", "string", "description", "工单描述"),
                                        "priority", Map.of("type", "string", "description", "优先级：P0/P1/P2/P3"),
                                        "assignee", Map.of("type", "string", "description", "指派给谁")
                                ),
                                "required", List.of("title", "description")
                        )
                ),
                Map.of(
                        "name", "queryMetrics",
                        "description", "查询指定服务的监控指标（QPS / 延迟 / 错误率）",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string", "description", "服务名"),
                                        "metric", Map.of("type", "string", "description", "指标名：qps / latency / error_rate"),
                                        "duration", Map.of("type", "string", "description", "时间范围：5m / 1h / 1d")
                                ),
                                "required", List.of("service", "metric")
                        )
                )
        );
    }

    /**
     * 根据工具名 + 参数执行工具（路由分发）
     */
    public ToolCallResult execute(String toolName, Map<String, Object> params) {
        log.info("[Tool] execute tool={} params={}", toolName, params);
        try {
            return switch (toolName) {
                case "queryServerStatus"   -> queryServerStatus(params);
                case "searchLogs"          -> searchLogs(params);
                case "queryDeployHistory"  -> queryDeployHistory(params);
                case "createTicket"        -> createTicket(params);
                case "queryMetrics"        -> queryMetrics(params);
                default -> ToolCallResult.builder()
                        .toolName(toolName).success(false)
                        .error("未知工具: " + toolName).build();
            };
        } catch (Exception e) {
            log.warn("[Tool] tool={} failed: {}", toolName, e.getMessage());
            return ToolCallResult.builder()
                    .toolName(toolName).success(false)
                    .error(e.getMessage()).build();
        }
    }

    // ========== 5 个工具实现 ==========

    private ToolCallResult queryServerStatus(Map<String, Object> params) {
        String hostname = (String) params.getOrDefault("hostname", "unknown");
        // Mock：真实场景对接 Prometheus / Zabbix / SSH
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hostname", hostname);
        data.put("status", "healthy");
        data.put("cpu", "23%");
        data.put("memory", "61%");
        data.put("disk", "45%");
        data.put("uptime", "12d 3h");
        data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return buildOk("queryServerStatus", data);
    }

    private ToolCallResult searchLogs(Map<String, Object> params) {
        String service = (String) params.getOrDefault("service", "unknown");
        String keyword = (String) params.getOrDefault("keyword", "ERROR");
        Object minutesObj = params.getOrDefault("minutes", 30);
        int minutes = minutesObj instanceof Number n ? n.intValue() : 30;

        // Mock：真实场景对接 ELK / Loki
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("keyword", keyword);
        data.put("timeRange", "last " + minutes + " min");
        data.put("totalHits", 42);
        data.put("sampleLogs", List.of(
                "2026-07-20 03:12:01 ERROR [" + service + "] NullPointerException at Main.java:42",
                "2026-07-20 03:15:33 ERROR [" + service + "] Connection refused: 192.168.100.142:3306",
                "2026-07-20 03:18:47 WARN  [" + service + "] Slow query detected: 5230ms"
        ));
        return buildOk("searchLogs", data);
    }

    private ToolCallResult queryDeployHistory(Map<String, Object> params) {
        String service = (String) params.getOrDefault("service", "unknown");
        // Mock：真实场景对接 Jenkins / GitLab CI
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("deploys", List.of(
                Map.of("version", "v2.3.1", "time", "2026-07-19 14:30", "author", "zhangsan", "status", "success"),
                Map.of("version", "v2.3.0", "time", "2026-07-18 10:15", "author", "lisi", "status", "success"),
                Map.of("version", "v2.2.9", "time", "2026-07-15 16:45", "author", "zhangsan", "status", "rollback")
        ));
        return buildOk("queryDeployHistory", data);
    }

    private ToolCallResult createTicket(Map<String, Object> params) {
        String title = (String) params.getOrDefault("title", "未命名工单");
        String desc = (String) params.getOrDefault("description", "");
        String priority = (String) params.getOrDefault("priority", "P2");
        String assignee = (String) params.getOrDefault("assignee", "未指派");
        // Mock：真实场景对接工单系统 API
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", "TK-" + System.currentTimeMillis() % 100000);
        data.put("title", title);
        data.put("description", desc);
        data.put("priority", priority);
        data.put("assignee", assignee);
        data.put("status", "OPEN");
        data.put("createTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return buildOk("createTicket", data);
    }

    private ToolCallResult queryMetrics(Map<String, Object> params) {
        String service = (String) params.getOrDefault("service", "unknown");
        String metric = (String) params.getOrDefault("metric", "qps");
        String duration = (String) params.getOrDefault("duration", "5m");
        // Mock：真实场景对接 Prometheus / Grafana
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("metric", metric);
        data.put("duration", duration);
        data.put("current", switch (metric) {
            case "qps" -> 1234.5;
            case "latency" -> 87.3;
            case "error_rate" -> 0.02;
            default -> "unknown metric";
        });
        data.put("unit", switch (metric) {
            case "qps" -> "req/s";
            case "latency" -> "ms";
            case "error_rate" -> "%";
            default -> "";
        });
        data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return buildOk("queryMetrics", data);
    }

    private ToolCallResult buildOk(String toolName, Map<String, Object> data) {
        try {
            return ToolCallResult.builder()
                    .toolName(toolName)
                    .success(true)
                    .data(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
                    .build();
        } catch (Exception e) {
            return ToolCallResult.builder()
                    .toolName(toolName).success(true)
                    .data(data.toString()).build();
        }
    }
}
package com.zihan.zhiwei.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpsAgentToolService 运维工具集测试")
class OpsAgentToolServiceTest {

    private OpsAgentToolService toolService;

    @BeforeEach
    void setUp() {
        toolService = new OpsAgentToolService(new ObjectMapper());
    }

    @Test
    @DisplayName("返回 5 个工具定义，含 name/description/parameters")
    void shouldReturnFiveToolDefinitions() {
        List<Map<String, Object>> tools = toolService.toolDefinitions();

        assertThat(tools).hasSize(5);
        assertThat(tools.get(0)).containsKeys("name", "description", "parameters");
    }

    @Test
    @DisplayName("工具名正确")
    void shouldHaveCorrectToolNames() {
        List<String> names = toolService.toolDefinitions().stream()
                .map(t -> (String) t.get("name"))
                .toList();

        assertThat(names).containsExactly(
                "queryServerStatus", "searchLogs", "queryDeployHistory", "createTicket", "queryMetrics");
    }

    @Nested
    @DisplayName("工具执行")
    class ToolExecutionTests {

        @Test
        @DisplayName("queryServerStatus → 返回主机状态")
        void shouldQueryServerStatus() {
            ToolCallResult result = toolService.execute("queryServerStatus",
                    Map.of("hostname", "redis-01"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("redis-01");
            assertThat(result.getData()).contains("healthy");
            assertThat(result.getData()).contains("cpu");
            assertThat(result.getData()).contains("23%");
        }

        @Test
        @DisplayName("searchLogs → 返回日志结果")
        void shouldSearchLogs() {
            ToolCallResult result = toolService.execute("searchLogs",
                    Map.of("service", "nginx", "keyword", "ERROR", "minutes", 60));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("nginx");
            assertThat(result.getData()).contains("ERROR");
            assertThat(result.getData()).contains("totalHits");
        }

        @Test
        @DisplayName("queryDeployHistory → 返回部署记录")
        void shouldQueryDeployHistory() {
            ToolCallResult result = toolService.execute("queryDeployHistory",
                    Map.of("service", "api-gateway"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("api-gateway");
            assertThat(result.getData()).contains("v2.3.1");
        }

        @Test
        @DisplayName("createTicket → 返回工单号")
        void shouldCreateTicket() {
            ToolCallResult result = toolService.execute("createTicket",
                    Map.of("title", "磁盘告警", "description", "磁盘使用率95%",
                            "priority", "P1", "assignee", "zhangsan"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("TK-");
            assertThat(result.getData()).contains("磁盘告警");
            assertThat(result.getData()).contains("P1");
            assertThat(result.getData()).contains("OPEN");
        }

        @Test
        @DisplayName("queryMetrics → 返回监控指标")
        void shouldQueryMetrics() {
            ToolCallResult result = toolService.execute("queryMetrics",
                    Map.of("service", "api", "metric", "qps", "duration", "5m"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("qps");
            assertThat(result.getData()).contains("req/s");
        }

        @Test
        @DisplayName("未知工具 → success=false")
        void shouldFailForUnknownTool() {
            ToolCallResult result = toolService.execute("unknownTool", Map.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("未知工具");
        }

        @Test
        @DisplayName("多指标查询")
        void shouldQueryDifferentMetrics() {
            ToolCallResult latency = toolService.execute("queryMetrics",
                    Map.of("service", "api", "metric", "latency", "duration", "1h"));
            ToolCallResult error = toolService.execute("queryMetrics",
                    Map.of("service", "api", "metric", "error_rate", "duration", "1d"));

            assertThat(latency.getData()).contains("latency");
            assertThat(latency.getData()).contains("ms");
            assertThat(error.getData()).contains("error_rate");
            assertThat(error.getData()).contains("%");
        }
    }
}

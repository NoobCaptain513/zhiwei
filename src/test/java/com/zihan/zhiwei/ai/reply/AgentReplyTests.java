package com.zihan.zhiwei.ai.reply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 回复体系测试")
class AgentReplyTests {

    // ──────────────────────────────────────────
    // AgentReply 编解码测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("AgentReply 编解码")
    class EncodeDecodeTests {

        @Test
        @DisplayName("纯文本 → encode 只含文本")
        void shouldEncodeTextOnly() {
            AgentReply reply = AgentReply.builder()
                    .text("服务器运行正常")
                    .cards(List.of())
                    .build();

            String encoded = reply.encode();

            assertThat(encoded).isEqualTo("服务器运行正常");
            assertThat(encoded).doesNotContain("CARDS");
        }

        @Test
        @DisplayName("文本 + 卡片 → encode 含分隔符")
        void shouldEncodeTextAndCards() {
            AgentReply.Card card = AgentReply.Card.builder()
                    .type("server")
                    .title("redis-01 状态")
                    .sourceId("server:redis-01")
                    .fields(Map.of("CPU", "23%", "内存", "61%"))
                    .build();
            AgentReply reply = AgentReply.builder()
                    .text("服务器状态如下：")
                    .cards(List.of(card))
                    .build();

            String encoded = reply.encode();

            assertThat(encoded).startsWith("服务器状态如下：");
            assertThat(encoded).contains("<!--CARDS:");
            assertThat(encoded).contains(":CARDS-->");
        }

        @Test
        @DisplayName("encode → decode 可还原")
        void shouldRoundTripEncodeDecode() {
            AgentReply.Card card = AgentReply.Card.builder()
                    .type("ticket")
                    .title("工单 - 磁盘告警")
                    .sourceId("ticket:TK-12345")
                    .fields(Map.of("工单号", "TK-12345", "优先级", "P1"))
                    .build();
            AgentReply original = AgentReply.builder()
                    .text("工单已创建")
                    .cards(List.of(card))
                    .build();

            String encoded = original.encode();
            AgentReply decoded = AgentReply.decode(encoded);

            assertThat(decoded.getText()).isEqualTo("工单已创建");
            assertThat(decoded.getCards()).hasSize(1);
            assertThat(decoded.getCards().get(0).getType()).isEqualTo("ticket");
            assertThat(decoded.getCards().get(0).getTitle()).isEqualTo("工单 - 磁盘告警");
            assertThat(decoded.getCards().get(0).getFields()).containsEntry("优先级", "P1");
        }

        @Test
        @DisplayName("decode 空字符串 → 空 AgentReply")
        void shouldDecodeEmpty() {
            AgentReply result = AgentReply.decode("");

            assertThat(result.getText()).isEmpty();
            assertThat(result.getCards()).isEmpty();
        }

        @Test
        @DisplayName("decode null → 空 AgentReply")
        void shouldDecodeNull() {
            AgentReply result = AgentReply.decode(null);

            assertThat(result.getText()).isNull();
            assertThat(result.getCards()).isEmpty();
        }

        @Test
        @DisplayName("decode 纯文本 → cards 为空")
        void shouldDecodePlainText() {
            AgentReply result = AgentReply.decode("服务器运行正常");

            assertThat(result.getText()).isEqualTo("服务器运行正常");
            assertThat(result.getCards()).isEmpty();
        }

        @Test
        @DisplayName("Card 编解码 → 可往返")
        void shouldRoundTripCard() {
            AgentReply.Card card = AgentReply.Card.builder()
                    .type("metric")
                    .title("QPS 监控")
                    .sourceId("metric:api:qps")
                    .fields(Map.of("服务", "api", "当前值", "1234 req/s"))
                    .build();

            String encoded = card.encode();
            AgentReply.Card decoded = AgentReply.Card.decode(encoded);

            assertThat(decoded.getType()).isEqualTo("metric");
            assertThat(decoded.getTitle()).isEqualTo("QPS 监控");
            assertThat(decoded.getSourceId()).isEqualTo("metric:api:qps");
            assertThat(decoded.getFields()).containsEntry("服务", "api");
        }
    }

    // ──────────────────────────────────────────
    // ResultCardAssembler 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("ResultCardAssembler 结构化卡片")
    class CardAssemblerTests {

        private ResultCardAssembler assembler = new ResultCardAssembler(new ObjectMapper());

        @Test
        @DisplayName("空列表 → 空卡片")
        void shouldReturnEmptyForNoResults() {
            assertThat(assembler.assemble(List.of())).isEmpty();
            assertThat(assembler.assemble(null)).isEmpty();
        }

        @Test
        @DisplayName("服务器状态 → server 卡片")
        void shouldBuildServerCard() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "hostname", "redis-01", "status", "healthy",
                            "cpu", "23%", "memory", "61%", "disk", "45%", "uptime", "12d"));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("queryServerStatus").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
            AgentReply.Card card = cards.get(0);
            assertThat(card.getType()).isEqualTo("server");
            assertThat(card.getTitle()).contains("redis-01");
            assertThat(card.getFields()).containsEntry("CPU", "23%");
            assertThat(card.getFields()).containsEntry("内存", "61%");
        }

        @Test
        @DisplayName("工单 → ticket 卡片")
        void shouldBuildTicketCard() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "ticketId", "TK-123", "title", "磁盘告警",
                            "priority", "P1", "assignee", "zhangsan", "status", "OPEN"));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("createTicket").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getType()).isEqualTo("ticket");
            assertThat(cards.get(0).getFields()).containsEntry("工单号", "TK-123");
            assertThat(cards.get(0).getFields()).containsEntry("优先级", "P1");
        }

        @Test
        @DisplayName("监控指标 → metric 卡片")
        void shouldBuildMetricCard() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "service", "api", "metric", "qps",
                            "current", 1234.5, "unit", "req/s", "duration", "5m"));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("queryMetrics").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getType()).isEqualTo("metric");
        }

        @Test
        @DisplayName("日志查询 → log 卡片")
        void shouldBuildLogCard() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "service", "nginx", "keyword", "ERROR",
                            "totalHits", 42, "timeRange", "last 30 min",
                            "sampleLogs", List.of("ERROR nginx: connection refused")));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("searchLogs").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getType()).isEqualTo("log");
        }

        @Test
        @DisplayName("部署记录 → deploy 卡片")
        void shouldBuildDeployCard() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of(
                            "service", "api-gateway",
                            "deploys", List.of(
                                    Map.of("version", "v2.3.1", "time", "2026-07-19", "author", "zs", "status", "success"))));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("queryDeployHistory").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getType()).isEqualTo("deploy");
        }

        @Test
        @DisplayName("同源重复 → 去重合并字段")
        void shouldDeduplicateBySource() throws Exception {
            String json1 = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("hostname", "redis-01", "status", "healthy", "cpu", "23%"));
            String json2 = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("hostname", "redis-01", "memory", "61%"));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("queryServerStatus").success(true).data(json1).build(),
                    ToolCallResult.builder().toolName("queryServerStatus").success(true).data(json2).build()));

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getFields()).containsEntry("CPU", "23%");
            assertThat(cards.get(0).getFields()).containsEntry("内存", "61%");
        }

        @Test
        @DisplayName("失败工具结果 → 跳过")
        void shouldSkipFailedResults() throws Exception {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("hostname", "redis-01"));

            List<AgentReply.Card> cards = assembler.assemble(List.of(
                    ToolCallResult.builder().toolName("queryServerStatus").success(false).error("timeout").build(),
                    ToolCallResult.builder().toolName("queryServerStatus").success(true).data(json).build()));

            assertThat(cards).hasSize(1);
        }
    }
}

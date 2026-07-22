package com.zihan.zhiwei.ai.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResultCollector 工具结果收集器测试")
class ToolResultCollectorTest {

    private ToolResultCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ToolResultCollector();
    }

    @Test
    @DisplayName("空收集器 → 无结果")
    void shouldBeEmptyInitially() {
        assertThat(collector.getAll()).isEmpty();
        assertThat(collector.successCount()).isZero();
        assertThat(collector.failCount()).isZero();
        assertThat(collector.toContextBlock()).isEmpty();
    }

    @Test
    @DisplayName("添加一条成功结果 → count+1")
    void shouldAddResult() {
        collector.add(buildResult("queryServerStatus", true, "{\"hostname\":\"redis-01\"}"));

        assertThat(collector.getAll()).hasSize(1);
        assertThat(collector.successCount()).isEqualTo(1);
        assertThat(collector.failCount()).isZero();
    }

    @Test
    @DisplayName("添加失败结果 → failCount+1")
    void shouldCountFailures() {
        ToolCallResult fail = ToolCallResult.builder()
                .toolName("searchLogs").success(false).error("timeout").build();
        collector.add(fail);

        assertThat(collector.successCount()).isZero();
        assertThat(collector.failCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("批量添加 → 正确计数")
    void shouldAddAll() {
        collector.addAll(List.of(
                buildResult("t1", true, "{}"),
                buildResult("t2", true, "{}"),
                buildResult("t3", false, null)));

        assertThat(collector.getAll()).hasSize(3);
        assertThat(collector.successCount()).isEqualTo(2);
        assertThat(collector.failCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear → 清空所有结果")
    void shouldClear() {
        collector.add(buildResult("t1", true, "{}"));
        collector.clear();

        assertThat(collector.getAll()).isEmpty();
        assertThat(collector.successCount()).isZero();
    }

    @Test
    @DisplayName("toContextBlock → 生成上下文文本")
    void shouldBuildContextBlock() {
        collector.add(buildResult("queryServerStatus", true,
                "{\"hostname\":\"redis-01\",\"status\":\"healthy\",\"cpu\":\"23%\"}"));
        collector.add(buildResult("searchLogs", true,
                "{\"service\":\"nginx\",\"totalHits\":42}"));

        String block = collector.toContextBlock();

        assertThat(block).contains("运维工具调用的结果");
        assertThat(block).contains("## queryServerStatus");
        assertThat(block).contains("redis-01");
        assertThat(block).contains("## searchLogs");
        assertThat(block).contains("nginx");
    }

    @Test
    @DisplayName("toContextBlock 含失败结果 → 显示错误信息")
    void shouldShowErrorInContextBlock() {
        ToolCallResult fail = ToolCallResult.builder()
                .toolName("unknownTool").success(false).error("未知工具").build();
        collector.add(fail);

        String block = collector.toContextBlock();

        assertThat(block).contains("调用失败");
        assertThat(block).contains("未知工具");
    }

    private static ToolCallResult buildResult(String toolName, boolean success, String data) {
        return ToolCallResult.builder()
                .toolName(toolName)
                .success(success)
                .data(data)
                .build();
    }
}

package com.zihan.zhiwei.ai.prompt;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiPromptService Prompt 模板化测试")
class AiPromptServiceTest {

    private AiPromptService service;

    @BeforeEach
    void setUp() {
        service = new AiPromptService();
        ReflectionTestUtils.setField(service, "appName", "智维");
    }

    @Test
    @DisplayName("fault 模板 → 含故障排查提示")
    void shouldBuildFaultPrompt() {
        String prompt = service.buildSystemPrompt(AgentIntent.FAULT);

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("故障排查助手");
        assertThat(prompt).contains("可能的原因");
        assertThat(prompt).contains("创建工单");
    }

    @Test
    @DisplayName("log 模板 → 含日志查询提示")
    void shouldBuildLogPrompt() {
        String prompt = service.buildSystemPrompt(AgentIntent.LOG);

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("日志查询助手");
        assertThat(prompt).contains("grep");
        assertThat(prompt).contains("kubectl logs");
    }

    @Test
    @DisplayName("deploy 模板 → 含部署回滚提示")
    void shouldBuildDeployPrompt() {
        String prompt = service.buildSystemPrompt(AgentIntent.DEPLOY);

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("部署发布助手");
        assertThat(prompt).contains("灰度");
        assertThat(prompt).contains("回滚");
    }

    @Test
    @DisplayName("ticket 模板 → 含工单创建提示")
    void shouldBuildTicketPrompt() {
        String prompt = service.buildSystemPrompt(AgentIntent.TICKET);

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("工单助手");
        assertThat(prompt).contains("优先级");
    }

    @Test
    @DisplayName("rag 模板 → 含知识问答提示")
    void shouldBuildRagPrompt() {
        String prompt = service.buildSystemPrompt(AgentIntent.RAG);

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("知识问答助手");
        assertThat(prompt).contains("知识库");
    }

    @Test
    @DisplayName("未知意图 → 默认 RAG 模板")
    void shouldDefaultToRagForUnknown() {
        String prompt = service.buildSystemPrompt("unknown");

        assertThat(prompt).contains("知识问答助手");
    }

    @Test
    @DisplayName("变量注入 → {{key}} 被替换")
    void shouldInjectVariables() {
        String prompt = service.buildSystemPrompt(AgentIntent.FAULT, Map.of("server", "redis-01"));

        assertThat(prompt).contains("智维");
    }

    @Test
    @DisplayName("null variables → 不抛异常")
    void shouldHandleNullVariables() {
        String prompt = service.buildSystemPrompt(AgentIntent.FAULT, null);

        assertThat(prompt).contains("智维");
    }

    @Test
    @DisplayName("defaultSystemPrompt → 通用提示")
    void shouldBuildDefaultPrompt() {
        String prompt = service.defaultSystemPrompt();

        assertThat(prompt).contains("智维");
        assertThat(prompt).contains("智能助手");
        assertThat(prompt).contains("简洁");
    }
}

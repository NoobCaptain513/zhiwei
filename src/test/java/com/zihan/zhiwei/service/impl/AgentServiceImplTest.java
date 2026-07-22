package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.intent.AgentIntentAnalyzer;
import com.zihan.zhiwei.ai.prompt.AiPromptService;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.RagContextBuilder;
import com.zihan.zhiwei.ai.rag.RagMessageAugmentor;
import com.zihan.zhiwei.ai.reply.*;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentServiceImpl 全链路测试")
class AgentServiceImplTest {

    @Mock private ConversationService conversationService;
    @Mock private ModelProviderRouter modelProviderRouter;
    @Mock private UsageRecorder usageRecorder;
    @Mock private AgentIntentAnalyzer intentAnalyzer;
    @Mock private AiPromptService promptService;
    @Mock private RagMessageAugmentor ragMessageAugmentor;
    @Mock private RagContextBuilder ragContextBuilder;
    @Mock private OpsAgentToolService opsAgentToolService;
    @Mock private AgentFallbackHandler fallbackHandler;

    private ToolResultCollector toolResultCollector = new ToolResultCollector();
    private AgentReplyService replyService;
    private AgentServiceImpl service;

    @Captor private ArgumentCaptor<List<ToolCallResult>> toolCaptor;

    @BeforeEach
    void setUp() {
        ResultCardAssembler assembler = new ResultCardAssembler(
                new com.fasterxml.jackson.databind.ObjectMapper());
        replyService = new AgentReplyService(assembler, toolResultCollector);

        service = new AgentServiceImpl(
                conversationService, modelProviderRouter, usageRecorder,
                intentAnalyzer, promptService,
                ragMessageAugmentor, ragContextBuilder,
                opsAgentToolService, toolResultCollector,
                fallbackHandler, replyService);
    }

    // ──────────────────────────────────────────
    // 全链路
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("全链路 Agent")
    class FullPipelineTests {

        @Test
        @DisplayName("fault 意图 → 查服务器状态 + 指标 → 返回 AgentResponse")
        void shouldExecuteFaultPipeline() {
            AgentRequest request = new AgentRequest("u1", null, "nginx-01 宕机了", null, false);
            Conversation conv = buildConv();
            when(conversationService.getOrCreate("u1", null)).thenReturn(conv);
            when(conversationService.saveMessage(eq(1L), eq("user"), eq("nginx-01 宕机了")))
                    .thenReturn(new Message());
            when(conversationService.listMessages(1L)).thenReturn(List.of());

            AgentIntent intent = AgentIntent.builder().primary(AgentIntent.FAULT)
                    .ranked(List.of(new AgentIntent.Score(AgentIntent.FAULT, 1.0))).build();
            when(intentAnalyzer.analyze("nginx-01 宕机了")).thenReturn(intent);
            when(promptService.buildSystemPrompt(anyString(), anyMap())).thenReturn("你是智维故障排查助手");

            ToolCallResult statusResult = ToolCallResult.builder()
                    .toolName("queryServerStatus").success(true)
                    .data("{\"hostname\":\"nginx-01\",\"cpu\":\"23%\",\"memory\":\"61%\"}").build();
            ToolCallResult metricResult = ToolCallResult.builder()
                    .toolName("queryMetrics").success(true)
                    .data("{\"service\":\"nginx-01\",\"metric\":\"error_rate\",\"current\":0.02,\"unit\":\"%\"}").build();
            when(opsAgentToolService.execute(eq("queryServerStatus"), anyMap())).thenReturn(statusResult);
            when(opsAgentToolService.execute(eq("queryMetrics"), anyMap())).thenReturn(metricResult);

            when(ragMessageAugmentor.augmentIfEnabled(anyList())).thenAnswer(inv -> inv.getArgument(0));
            ProviderChatResponse providerResp = new ProviderChatResponse(
                    "nginx-01 目前运行正常，cpu 23%，建议检查内存使用率...",
                    "qwen-plus", "spring-ai-alibaba", 200, 100, 300);
            when(modelProviderRouter.executeWithFailover(any())).thenReturn(
                    new FailoverResult(providerResp, "spring-ai-alibaba", "spring-ai-alibaba", false, 350L, List.of()));

            when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString())).thenReturn(null);
            when(conversationService.saveMessage(eq(1L), eq("assistant"), anyString()))
                    .thenAnswer(inv -> {
                        Message msg = new Message();
                        msg.setId(100L);
                        msg.setConversationId(1L);
                        msg.setRole("assistant");
                        msg.setContent(inv.getArgument(2));
                        return msg;
                    });

            AgentResponse response = service.agent(request);

            assertThat(response.getConversationId()).isEqualTo(1L);
            assertThat(response.getMessageId()).isEqualTo(100L);
            assertThat(response.getIntent()).isEqualTo(AgentIntent.FAULT);
            assertThat(response.getProvider()).isEqualTo("spring-ai-alibaba");
            assertThat(response.isDegraded()).isFalse();

            verify(conversationService).getOrCreate("u1", null);
            verify(intentAnalyzer).analyze("nginx-01 宕机了");
            verify(opsAgentToolService).execute(eq("queryServerStatus"), anyMap());
            verify(opsAgentToolService).execute(eq("queryMetrics"), anyMap());
            verify(modelProviderRouter).executeWithFailover(any());
            verify(usageRecorder).record(eq(1L), eq(100L), any(), eq("agent"), anyLong(), eq(false));
        }

        @Test
        @DisplayName("log 意图 → 搜索日志")
        void shouldExecuteLogPipeline() {
            AgentRequest request = new AgentRequest("u1", null, "查看 nginx 错误日志", null, false);
            Conversation conv = buildConv();
            when(conversationService.getOrCreate("u1", null)).thenReturn(conv);
            when(conversationService.saveMessage(anyLong(), eq("user"), anyString())).thenReturn(new Message());
            when(conversationService.listMessages(1L)).thenReturn(List.of());

            AgentIntent intent = AgentIntent.builder().primary(AgentIntent.LOG)
                    .ranked(List.of(new AgentIntent.Score(AgentIntent.LOG, 1.0))).build();
            when(intentAnalyzer.analyze(anyString())).thenReturn(intent);
            when(promptService.buildSystemPrompt(anyString(), anyMap())).thenReturn("你是智维日志查询助手");

            ToolCallResult logResult = ToolCallResult.builder()
                    .toolName("searchLogs").success(true)
                    .data("{\"service\":\"nginx\",\"keyword\":\"ERROR\",\"totalHits\":42}").build();
            when(opsAgentToolService.execute(eq("searchLogs"), anyMap())).thenReturn(logResult);
            when(ragMessageAugmentor.augmentIfEnabled(anyList())).thenAnswer(inv -> inv.getArgument(0));
            when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString())).thenReturn(null);

            ProviderChatResponse providerResp = new ProviderChatResponse(
                    "日志中发现 42 条 ERROR 记录...", "qwen-plus", "spring-ai-alibaba", 150, 80, 230);
            when(modelProviderRouter.executeWithFailover(any())).thenReturn(
                    new FailoverResult(providerResp, "spring-ai-alibaba", "spring-ai-alibaba", false, 200L, List.of()));
            when(conversationService.saveMessage(eq(1L), eq("assistant"), anyString()))
                    .thenAnswer(inv -> buildMsg(200L, inv.getArgument(2)));

            AgentResponse response = service.agent(request);

            assertThat(response.getIntent()).isEqualTo(AgentIntent.LOG);
            verify(opsAgentToolService).execute(eq("searchLogs"), anyMap());
        }

        @Test
        @DisplayName("deploy 意图 → 查部署历史")
        void shouldExecuteDeployPipeline() {
            setupCommonMocks(AgentIntent.DEPLOY, "部署 web-server v2.3.1");
            ToolCallResult deployResult = ToolCallResult.builder()
                    .toolName("queryDeployHistory").success(true)
                    .data("{\"service\":\"web-server\",\"deploys\":[{\"version\":\"v2.3.1\"}]}").build();
            when(opsAgentToolService.execute(eq("queryDeployHistory"), anyMap())).thenReturn(deployResult);

            AgentResponse response = service.agent(new AgentRequest("u1", null, "部署 web-server v2.3.1", null, false));

            assertThat(response.getIntent()).isEqualTo(AgentIntent.DEPLOY);
            verify(opsAgentToolService).execute(eq("queryDeployHistory"), anyMap());
        }

        @Test
        @DisplayName("ticket 意图 → 创建工单")
        void shouldExecuteTicketPipeline() {
            setupCommonMocks(AgentIntent.TICKET, "创建磁盘告警工单");
            ToolCallResult ticketResult = ToolCallResult.builder()
                    .toolName("createTicket").success(true)
                    .data("{\"ticketId\":\"TK-123\",\"status\":\"OPEN\"}").build();
            when(opsAgentToolService.execute(eq("createTicket"), anyMap())).thenReturn(ticketResult);

            AgentResponse response = service.agent(new AgentRequest("u1", null, "创建磁盘告警工单", null, false));

            assertThat(response.getIntent()).isEqualTo(AgentIntent.TICKET);
            verify(opsAgentToolService).execute(eq("createTicket"), anyMap());
        }

        @Test
        @DisplayName("RAG 意图 → 不调工具，纯走 RAG 增强")
        void shouldSkipToolsForRag() {
            setupCommonMocks(AgentIntent.RAG, "Redis 集群的原理是什么");
            // RAG 意图不调任何工具，只走 RagMessageAugmentor 增强

            AgentResponse response = service.agent(new AgentRequest("u1", null, "Redis 集群的原理是什么", null, false));

            assertThat(response.getIntent()).isEqualTo(AgentIntent.RAG);
            verify(opsAgentToolService, never()).execute(anyString(), anyMap());
            verify(ragMessageAugmentor).augmentIfEnabled(anyList());
        }
    }

    // ──────────────────────────────────────────
    // 降级兜底
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("降级兜底")
    class FallbackTests {

        @Test
        @DisplayName("fallback 非 null → 使用兜底 AgentReply")
        void shouldUseFallbackReplyWhenNeeded() {
            setupCommonMocks(AgentIntent.FAULT, "Redis 宕机了");
            AgentReply fallbackReply = AgentReply.builder()
                    .text("兜底回复，参考知识库卡片")
                    .cards(List.of())
                    .intent("fault")
                    .build();
            when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), eq(AgentIntent.FAULT)))
                    .thenReturn(fallbackReply);

            AgentResponse response = service.agent(new AgentRequest("u1", null, "Redis 宕机了", null, false));

            assertThat(response.getContent()).isEqualTo("兜底回复，参考知识库卡片");
        }
    }

    // ──────────────────────────────────────────
    // 工具搜集器清空
    // ──────────────────────────────────────────

    @Test
    @DisplayName("每次 agent 调用前清空工具收集器")
    void shouldClearCollectorBeforeEachCall() {
        toolResultCollector.add(ToolCallResult.builder().toolName("prev").success(true).build());
        assertThat(toolResultCollector.getAll()).hasSize(1);

        setupCommonMocks(AgentIntent.FAULT, "test");

        service.agent(new AgentRequest("u1", null, "test", null, false));

        assertThat(toolResultCollector.getAll()).hasSize(2);
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private void setupCommonMocks(String intentName, String message) {
        Conversation conv = buildConv();
        when(conversationService.getOrCreate("u1", null)).thenReturn(conv);
        when(conversationService.saveMessage(anyLong(), eq("user"), anyString())).thenReturn(new Message());
        when(conversationService.listMessages(1L)).thenReturn(List.of());

        when(intentAnalyzer.analyze(anyString())).thenReturn(
                AgentIntent.builder().primary(intentName)
                        .ranked(List.of(new AgentIntent.Score(intentName, 1.0))).build());
        when(promptService.buildSystemPrompt(anyString(), anyMap())).thenReturn("system prompt for " + intentName);

        // 所有工具调用返回 successful mock
        ToolCallResult dummyResult = ToolCallResult.builder()
                .toolName("mock").success(true).data("{}").build();
        when(opsAgentToolService.execute(anyString(), anyMap())).thenReturn(dummyResult);

        when(ragMessageAugmentor.augmentIfEnabled(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString())).thenReturn(null);

        ProviderChatResponse providerResp = new ProviderChatResponse(
                "模型回复...", "qwen-plus", "spring-ai-alibaba", 100, 50, 150);
        when(modelProviderRouter.executeWithFailover(any())).thenReturn(
                new FailoverResult(providerResp, "spring-ai-alibaba", "spring-ai-alibaba", false, 100L, List.of()));
        when(conversationService.saveMessage(eq(1L), eq("assistant"), anyString()))
                .thenAnswer(inv -> buildMsg(999L, inv.getArgument(2)));
    }

    private static Conversation buildConv() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId("u1");
        conv.setTitle("测试会话");
        conv.setCreateTime(LocalDateTime.now());
        return conv;
    }

    private static Message buildMsg(Long id, String content) {
        Message msg = new Message();
        msg.setId(id);
        msg.setConversationId(1L);
        msg.setRole("assistant");
        msg.setContent(content);
        return msg;
    }
}

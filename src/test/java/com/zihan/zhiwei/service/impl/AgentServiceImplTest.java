package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.intent.AgentIntentAnalyzer;
import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedEvent;
import com.zihan.zhiwei.ai.memory.ConversationTurnCompletedPublisher;
import com.zihan.zhiwei.ai.memory.MemoryContext;
import com.zihan.zhiwei.ai.memory.MemoryContextRenderer;
import com.zihan.zhiwei.ai.memory.MemoryContextService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.prompt.AiPromptService;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.RagContextBuilder;
import com.zihan.zhiwei.ai.rag.RagMessageAugmentor;
import com.zihan.zhiwei.ai.rag.agentic.AgenticRagOrchestrator;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.ai.rag.agentic.model.Citation;
import com.zihan.zhiwei.ai.reply.*;
import com.zihan.zhiwei.ai.safety.SpringAiSafetyAdvisor;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import com.zihan.zhiwei.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    @Mock private AgentClarificationService clarificationService;
    @Mock private SpringAiSafetyAdvisor safetyAdvisor;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ObjectProvider<ToolResultCollector> toolResultCollectorProvider;
    @Mock private AssistantCompletionService assistantCompletionService;
    @Mock private AgenticRagOrchestrator agenticRagOrchestrator;
    @Mock private MemoryContextService memoryContextService;
    @Mock private MemoryContextRenderer memoryContextRenderer;

    private ToolResultCollector toolResultCollector = new ToolResultCollector();
    private AgentReplyService replyService;
    private AgentServiceImpl service;

    @Captor private ArgumentCaptor<List<ToolCallResult>> toolCaptor;

    @BeforeEach
    void setUp() {
        ResultCardAssembler assembler = new ResultCardAssembler(
                new com.fasterxml.jackson.databind.ObjectMapper());
        replyService = new AgentReplyService(
                assembler, new com.fasterxml.jackson.databind.ObjectMapper());

        // 安全校验默认放行
        when(safetyAdvisor.check(anyString(), anyString())).thenReturn(null);
        // 幂等默认未命中；空 key 返回 disabled lease。
        when(idempotencyService.fingerprint(anyString(), any())).thenReturn("fingerprint");
        when(idempotencyService.resolve(anyString(), anyString(), nullable(String.class), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        when(idempotencyService.acquire(anyString(), anyString(), nullable(String.class), anyString(), anyInt()))
                .thenAnswer(inv -> IdempotentRequestCache.IdempotencyLease.disabled(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        // ObjectProvider 每次返回新实例，模拟真实的 prototype scope（避免状态累积）
        when(toolResultCollectorProvider.getObject()).thenAnswer(inv -> new ToolResultCollector());

        service = new AgentServiceImpl(
                conversationService, modelProviderRouter, usageRecorder,
                intentAnalyzer, promptService,
                ragMessageAugmentor, ragContextBuilder,
                toolResultCollectorProvider, fallbackHandler, replyService,
                clarificationService, safetyAdvisor, idempotencyService, assistantCompletionService,
                new com.fasterxml.jackson.databind.ObjectMapper());

        // opsAgentToolService 是 @Autowired(required=false) 字段（不在构造器里），反射注入 mock
        ReflectionTestUtils.setField(service, "opsAgentToolService", opsAgentToolService);
    }

    // ──────────────────────────────────────────
    // 全链路
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("全链路 Agent")
    class FullPipelineTests {

        @Test
        @DisplayName("记忆开启时同步与流式复用统一上下文且当前消息只发送一次")
        void shouldUseSameMemoryContextForSyncAndStreamWithoutDuplicatingCurrentMessage() {
            String current = "Redis MOVED 怎么处理";
            setupCommonMocks(AgentIntent.RAG, current);
            ConversationTurnCompletedPublisher publisher = mock(ConversationTurnCompletedPublisher.class);
            ReflectionTestUtils.setField(service, "turnCompletedPublisher", publisher);
            enableMemoryInjection();
            ReflectionTestUtils.setField(service, "agenticRagEnabled", true);
            ReflectionTestUtils.setField(service, "agenticRagOrchestrator", agenticRagOrchestrator);

            Message prior = message(7L, "assistant", "prior answer");
            MemoryContext context = new MemoryContext(current, Optional.empty(), Optional.empty(),
                    List.of(), List.of(prior), 20, 12000);
            when(memoryContextService.buildContext("u1", 1L, current, 12000)).thenReturn(context);
            when(memoryContextRenderer.renderSystemBlock(context)).thenReturn("MEMORY BLOCK");
            when(memoryContextRenderer.renderAgenticHistory(context)).thenReturn("MEMORY HISTORY");
            when(agenticRagOrchestrator.execute(any())).thenReturn(AgenticRagResult.notRequired());
            when(modelProviderRouter.streamChatWithFailover(any(), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<String> consumer = invocation.getArgument(1);
                consumer.accept("模型回复...");
                return new StreamResult("qwen-plus", "spring-ai-alibaba", 100, 50, 150);
            });
            when(assistantCompletionService.saveCompletion(anyString(), anyLong(), anyLong(),
                    anyString(), any(), eq("agent"), anyLong(), eq(false)))
                    .thenReturn(buildMsg(1001L, "saved"));

            service.agent(new AgentRequest("u1", null, current, null, false, null, null));
            service.streamAgent(new AgentRequest("u1", null, current, null, false, null, null),
                    ignored -> { }, ignored -> { });

            verify(publisher, times(1)).publishAfterCommit(
                    new ConversationTurnCompletedEvent("u1", 1L, 998L, 999L));
            verify(assistantCompletionService, times(1)).saveCompletion(
                    eq("u1"), eq(1L), eq(998L), anyString(), any(),
                    eq("agent"), anyLong(), eq(false));

            ArgumentCaptor<ProviderChatRequest> syncRequest = ArgumentCaptor.forClass(ProviderChatRequest.class);
            verify(modelProviderRouter).executeWithFailover(syncRequest.capture());
            ArgumentCaptor<ProviderChatRequest> streamRequest = ArgumentCaptor.forClass(ProviderChatRequest.class);
            verify(modelProviderRouter).streamChatWithFailover(streamRequest.capture(), any());
            assertThat(syncRequest.getValue().messages()).isEqualTo(streamRequest.getValue().messages());
            assertThat(syncRequest.getValue().messages()).extracting(message -> message.content())
                    .anySatisfy(content -> assertThat(content).contains("MEMORY BLOCK"));
            assertThat(syncRequest.getValue().messages())
                    .filteredOn(message -> "user".equals(message.role()) && current.equals(message.content()))
                    .hasSize(1);

            ArgumentCaptor<AgenticRagRequest> ragRequests = ArgumentCaptor.forClass(AgenticRagRequest.class);
            verify(agenticRagOrchestrator, times(2)).execute(ragRequests.capture());
            assertThat(ragRequests.getAllValues()).extracting(AgenticRagRequest::historyContext)
                    .containsOnly("MEMORY HISTORY");
            verify(memoryContextService, times(2)).buildContext("u1", 1L, current, 12000);
            verify(memoryContextRenderer, times(2)).renderSystemBlock(context);
            verify(memoryContextRenderer, times(2)).renderAgenticHistory(context);
            verify(conversationService, never()).listMessages(1L);
        }

        @ParameterizedTest
        @CsvSource({"false,true", "true,false"})
        @DisplayName("任一记忆开关关闭时保留最后20条 provider 与最后6条 Agentic 历史")
        void shouldKeepLegacyHistoryWhenMemoryInjectionIsDisabled(boolean enabled, boolean injectEnabled) {
            String current = "current question";
            setupCommonMocks(AgentIntent.RAG, current);
            MemoryProperties properties = new MemoryProperties();
            properties.setEnabled(enabled);
            properties.setInjectEnabled(injectEnabled);
            ReflectionTestUtils.setField(service, "memoryProperties", properties);
            ReflectionTestUtils.setField(service, "memoryContextService", memoryContextService);
            ReflectionTestUtils.setField(service, "memoryContextRenderer", memoryContextRenderer);
            ReflectionTestUtils.setField(service, "agenticRagEnabled", true);
            ReflectionTestUtils.setField(service, "agenticRagOrchestrator", agenticRagOrchestrator);
            List<Message> history = new ArrayList<>();
            for (int i = 1; i <= 24; i++) {
                history.add(message((long) i, i % 2 == 0 ? "assistant" : "user", "history-" + i));
            }
            history.add(message(25L, "user", current));
            when(conversationService.listMessages(1L)).thenReturn(history);
            when(agenticRagOrchestrator.execute(any())).thenReturn(AgenticRagResult.notRequired());

            service.agent(new AgentRequest("u1", null, current, null, false, null, null));

            ArgumentCaptor<ProviderChatRequest> providerRequest = ArgumentCaptor.forClass(ProviderChatRequest.class);
            verify(modelProviderRouter).executeWithFailover(providerRequest.capture());
            assertThat(providerRequest.getValue().messages()).hasSize(21);
            assertThat(providerRequest.getValue().messages().get(1).content()).isEqualTo("history-6");
            assertThat(providerRequest.getValue().messages().getLast().content()).isEqualTo(current);
            ArgumentCaptor<AgenticRagRequest> ragRequest = ArgumentCaptor.forClass(AgenticRagRequest.class);
            verify(agenticRagOrchestrator).execute(ragRequest.capture());
            assertThat(ragRequest.getValue().historyContext())
                    .contains("history-20", "history-24", current)
                    .doesNotContain("history-19");
            verifyNoInteractions(memoryContextService, memoryContextRenderer);
        }

        @Test
        @DisplayName("fault 意图 → 查服务器状态 + 指标 → 返回 AgentResponse")
        void shouldExecuteFaultPipeline() {
            AgentRequest request = new AgentRequest("u1", null, "nginx-01 宕机了", null, false, null, null);
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

            when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString(), anyList())).thenReturn(null);
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
            assertThat(response.getCards()).extracting(AgentReply.Card::getType)
                    .containsExactly("server", "metric");

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
            AgentRequest request = new AgentRequest("u1", null, "查看 nginx 错误日志", null, false, null, null);
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
            when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString(), anyList())).thenReturn(null);

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

            AgentResponse response = service.agent(new AgentRequest("u1", null, "部署 web-server v2.3.1", null, false, null, null));

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

            AgentResponse response = service.agent(new AgentRequest("u1", null, "创建磁盘告警工单", null, false, null, null));

            assertThat(response.getIntent()).isEqualTo(AgentIntent.TICKET);
            verify(opsAgentToolService).execute(eq("createTicket"), anyMap());
        }

        @Test
        @DisplayName("RAG 意图 → 不调工具，纯走 RAG 增强")
        void shouldSkipToolsForRag() {
            setupCommonMocks(AgentIntent.RAG, "Redis 集群的原理是什么");
            // RAG 意图不调任何工具，只走 RagMessageAugmentor 增强

            AgentResponse response = service.agent(new AgentRequest("u1", null, "Redis 集群的原理是什么", null, false, null, null));

            assertThat(response.getIntent()).isEqualTo(AgentIntent.RAG);
            verify(opsAgentToolService, never()).execute(anyString(), anyMap());
            verify(ragMessageAugmentor).augmentIfEnabled(anyList(), isNull());
        }

        @Test
        @DisplayName("chatOnly=true → 跳过所有工具")
        void shouldSkipAllToolsInChatOnlyMode() {
            setupCommonMocks(AgentIntent.FAULT, "只分析，不执行工具");

            service.agent(new AgentRequest("u1", null, "只分析，不执行工具", null, true, null, null));

            verify(opsAgentToolService, never()).execute(anyString(), anyMap());
        }

        @Test
        @DisplayName("启用 Agentic RAG → 返回验证答案和引用卡片，不执行旧增强")
        void shouldUseAgenticRagResultWithCitationCards() {
            setupCommonMocks(AgentIntent.RAG, "Redis MOVED 怎么处理");
            ReflectionTestUtils.setField(service, "agenticRagEnabled", true);
            ReflectionTestUtils.setField(service, "agenticRagOrchestrator", agenticRagOrchestrator);
            AgenticRagResult grounded = new AgenticRagResult(true, "按手册处理 [E7]", List.of(
                    new Citation("E7", 7L, 3L, "runbook-7", "Redis 手册", 0.91)),
                    2, true, false, "ANSWERED", "spring-ai-alibaba", "qwen-plus",
                    20, 10, 30, false, 100);
            when(agenticRagOrchestrator.execute(any())).thenReturn(grounded);

            AgentResponse response = service.agent(new AgentRequest(
                    "u1", null, "Redis MOVED 怎么处理", null, false, null, null));

            assertThat(response.getContent()).isEqualTo("按手册处理 [E7]");
            assertThat(response.getCards()).singleElement().satisfies(card -> {
                assertThat(card.getType()).isEqualTo("rag");
                assertThat(card.getSourceId()).isEqualTo("runbook-7");
                assertThat(card.getFields()).containsEntry("证据ID", "E7");
            });
            verify(ragMessageAugmentor, never()).augmentIfEnabled(anyList(), nullable(String.class));
            verify(modelProviderRouter, never()).executeWithFailover(any());
        }

        @Test
        @DisplayName("Agentic 分类无需检索 → 普通生成且不执行旧 RAG 增强")
        void shouldSkipLegacyRetrievalWhenAgenticClassifierBypassesRag() {
            setupCommonMocks(AgentIntent.RAG, "你好");
            ReflectionTestUtils.setField(service, "agenticRagEnabled", true);
            ReflectionTestUtils.setField(service, "agenticRagOrchestrator", agenticRagOrchestrator);
            when(agenticRagOrchestrator.execute(any())).thenReturn(AgenticRagResult.notRequired());

            AgentResponse response = service.agent(new AgentRequest(
                    "u1", null, "你好", null, false, null, null));

            assertThat(response.getContent()).isEqualTo("模型回复...");
            verify(ragMessageAugmentor, never()).augmentIfEnabled(anyList(), nullable(String.class));
            verify(modelProviderRouter).executeWithFailover(any());
        }

        @Test
        @DisplayName("流式 Agentic RAG → 验证完成后才发送答案和引用")
        void shouldEmitVerifiedAgenticAnswerAndCitations() {
            setupCommonMocks(AgentIntent.RAG, "Redis MOVED 怎么处理");
            ReflectionTestUtils.setField(service, "agenticRagEnabled", true);
            ReflectionTestUtils.setField(service, "agenticRagOrchestrator", agenticRagOrchestrator);
            AgenticRagResult grounded = new AgenticRagResult(true, "验证后的答案 [E7]", List.of(
                    new Citation("E7", 7L, 3L, "runbook-7", "Redis 手册", 0.91)),
                    2, true, false, "ANSWERED", "spring-ai-alibaba", "qwen-plus",
                    20, 10, 30, false, 100);
            when(agenticRagOrchestrator.execute(any())).thenReturn(grounded);
            when(assistantCompletionService.saveCompletion(anyString(), anyLong(), anyLong(),
                    anyString(), any(), eq("agent"), anyLong(), eq(false)))
                    .thenReturn(buildMsg(1000L, "saved"));
            List<String> tokens = new ArrayList<>();
            List<String> cards = new ArrayList<>();

            var result = service.streamAgent(new AgentRequest(
                    "u1", null, "Redis MOVED 怎么处理", null, false, null, null),
                    tokens::add, cards::add);

            assertThat(tokens).containsExactly("验证后的答案 [E7]");
            assertThat(cards).singleElement().asString().contains("runbook-7");
            assertThat(result.getContent()).isEqualTo("验证后的答案 [E7]");
            verify(modelProviderRouter, never()).streamChatWithFailover(any(), any());
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
            when(fallbackHandler.fallbackIfNeeded(
                    anyString(), anyString(), eq(AgentIntent.FAULT), anyList()))
                    .thenReturn(fallbackReply);

            AgentResponse response = service.agent(new AgentRequest("u1", null, "Redis 宕机了", null, false, null, null));

            assertThat(response.getContent()).isEqualTo("兜底回复，参考知识库卡片");
        }
    }

    // ──────────────────────────────────────────
    // 工具搜集器清空
    // ──────────────────────────────────────────

    @Test
    @DisplayName("每次 agent 调用使用独立的 collector 实例（prototype scope）")
    void shouldUseIndependentCollectorForEachCall() {
        // 验证 prototype scope：每次调用获取新实例，互不干扰
        ToolResultCollector collector1 = new ToolResultCollector();
        ToolResultCollector collector2 = new ToolResultCollector();
        
        when(toolResultCollectorProvider.getObject())
                .thenReturn(collector1)  // 第一次调用
                .thenReturn(collector2); // 第二次调用

        setupCommonMocks(AgentIntent.FAULT, "test");

        // 第一次调用
        service.agent(new AgentRequest("u1", null, "test", null, false, null, null));
        assertThat(collector1.getAll()).hasSize(2);  // 本次调用产生 2 个工具结果

        // 第二次调用
        service.agent(new AgentRequest("u1", null, "test", null, false, null, null));
        assertThat(collector1.getAll()).hasSize(2);  // 第一个 collector 状态不变
        assertThat(collector2.getAll()).hasSize(2);  // 第二个 collector 有独立状态
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private void setupCommonMocks(String intentName, String message) {
        Conversation conv = buildConv();
        when(conversationService.getOrCreate("u1", null)).thenReturn(conv);
        when(conversationService.saveMessage(anyLong(), eq("user"), anyString()))
                .thenReturn(buildMsg(998L, message));
        when(conversationService.listMessages(1L)).thenReturn(List.of());

        when(intentAnalyzer.analyze(anyString())).thenReturn(
                AgentIntent.builder().primary(intentName)
                        .ranked(List.of(new AgentIntent.Score(intentName, 1.0))).build());
        when(promptService.buildSystemPrompt(anyString(), anyMap())).thenReturn("system prompt for " + intentName);

        // 所有工具调用返回 successful mock
        ToolCallResult dummyResult = ToolCallResult.builder()
                .toolName("mock").success(true).data("{}").build();
        when(opsAgentToolService.execute(anyString(), anyMap())).thenReturn(dummyResult);

        // 同时 mock 单参数和双参数版本的 augmentIfEnabled
        when(ragMessageAugmentor.augmentIfEnabled(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ragMessageAugmentor.augmentIfEnabled(anyList(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(fallbackHandler.fallbackIfNeeded(anyString(), anyString(), anyString(), anyList())).thenReturn(null);

        ProviderChatResponse providerResp = new ProviderChatResponse(
                "模型回复...", "qwen-plus", "spring-ai-alibaba", 100, 50, 150);
        when(modelProviderRouter.executeWithFailover(any())).thenReturn(
                new FailoverResult(providerResp, "spring-ai-alibaba", "spring-ai-alibaba", false, 100L, List.of()));
        when(conversationService.saveMessage(eq(1L), eq("assistant"), anyString()))
                .thenAnswer(inv -> buildMsg(999L, inv.getArgument(2)));
    }

    private void enableMemoryInjection() {
        MemoryProperties properties = new MemoryProperties();
        properties.setEnabled(true);
        properties.setInjectEnabled(true);
        ReflectionTestUtils.setField(service, "memoryProperties", properties);
        ReflectionTestUtils.setField(service, "memoryContextService", memoryContextService);
        ReflectionTestUtils.setField(service, "memoryContextRenderer", memoryContextRenderer);
    }

    private static Message message(Long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(1L);
        message.setRole(role);
        message.setContent(content);
        return message;
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

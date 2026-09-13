package com.zihan.zhiwei.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.intent.AgentIntentAnalyzer;
import com.zihan.zhiwei.ai.prompt.AiPromptService;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.RagContextBuilder;
import com.zihan.zhiwei.ai.rag.RagMessageAugmentor;
import com.zihan.zhiwei.ai.rag.agentic.AgenticRagOrchestrator;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.ai.rag.agentic.model.Citation;
import com.zihan.zhiwei.ai.reply.AgentClarificationService;
import com.zihan.zhiwei.ai.reply.AgentFallbackHandler;
import com.zihan.zhiwei.ai.reply.AgentReply;
import com.zihan.zhiwei.ai.reply.AgentReplyService;
import com.zihan.zhiwei.ai.safety.SpringAiSafetyAdvisor;
import com.zihan.zhiwei.ai.stream.AgentStreamResult;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ReliableToolExecutor;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolInvocation;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.AgentService;
import com.zihan.zhiwei.service.ConversationService;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * D14+D15: Agent 全链路实现。
 * D15: 新增 streamAgent() 流式版本。
 * D29: 意图置信度不足时主动引导。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final ConversationService conversationService;
    private final ModelProviderRouter modelProviderRouter;
    private final UsageRecorder usageRecorder;
    private final AgentIntentAnalyzer intentAnalyzer;
    private final AiPromptService promptService;
    private final RagMessageAugmentor ragMessageAugmentor;
    private final RagContextBuilder ragContextBuilder;
    /**
     * P1-6 修复：改为可选注入，Mock 服务未启用时（zhiwei.ai.tool.mock-enabled != true）
     * 不会影响应用启动，simulateToolCalls 中做 null 检查。
     */
    @Autowired(required = false)
    private OpsAgentToolService opsAgentToolService;
    @Autowired(required = false)
    private ReliableToolExecutor reliableToolExecutor;
    /**
     * 修复 ScopeNotActiveException：ToolResultCollector 改为 prototype scope，
     * 通过 ObjectProvider 每次调用时获取一个全新实例，
     * 避免 @RequestScope 在 AiStreamAdvice 线程池中找不到 request 上下文的问题。
     */
    private final ObjectProvider<ToolResultCollector> toolResultCollectorProvider;
    private final AgentFallbackHandler fallbackHandler;
    private final AgentReplyService replyService;
    private final AgentClarificationService clarificationService;
    private final SpringAiSafetyAdvisor safetyAdvisor;
    private final IdempotentRequestCache idempotencyService;
    private final AssistantCompletionService assistantCompletionService;

    @Autowired(required = false)
    private AgenticRagOrchestrator agenticRagOrchestrator;

    @Value("${zhiwei.ai.rag.agentic.enabled:false}")
    private boolean agenticRagEnabled;

    /**
     * P0-3 修复：注入 ObjectMapper 用于 JSON 序列化卡片数据，
     * 替代原来 Result.ok(...).toString() 产生的非 JSON 格式。
     */
    private final ObjectMapper objectMapper;

    // ==================== D14+D29: 同步 Agent ====================

    @Override
    @Transactional
    public AgentResponse agent(AgentRequest request) {
        // 安全检查：长度/频率/敏感词/Prompt注入
        String rejectReason = safetyAdvisor.check(request.userId(), request.message());
        if (rejectReason != null) {
            throw new BusinessException(rejectReason);
        }

        // 幂等快速路径：同一 namespace + idempotencyKey 已处理过 → 直接返回首次结果
        String idemNamespace = request.approvalId() == null || request.approvalId().isBlank()
                ? "agent" : "agent-approved";
        String requestFingerprint = idempotencyService.fingerprint(idemNamespace, request);
        Optional<AgentResponse> idemCached = idempotencyService.resolve(
                idemNamespace, request.userId(), request.idempotencyKey(), AgentResponse.class,
                requestFingerprint);
        if (idemCached.isPresent()) {
            return idemCached.get();
        }

        IdempotentRequestCache.IdempotencyLease idemLease = idempotencyService.acquire(
                idemNamespace, request.userId(), request.idempotencyKey(), requestFingerprint, 300);
        if (!idemLease.acquired() && idemLease.enabled()) {
            Optional<AgentResponse> waited = idempotencyService.resolve(
                    idemNamespace, request.userId(), request.idempotencyKey(), AgentResponse.class,
                    requestFingerprint);
            if (waited.isPresent()) {
                return waited.get();
            }
            throw new BusinessException("幂等处理超时，请稍后重试");
        }

        try {

        // prototype scope：每次调用获取一个全新实例，线程安全，无 request 上下文依赖
        ToolResultCollector toolResultCollector = toolResultCollectorProvider.getObject();

        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        List<Message> history = conversationService.listMessages(conversation.getId());

        AgentIntent intent = intentAnalyzer.analyze(request.message());
        String primaryIntent = intent.getPrimary();
        log.info("[Agent] userId={} intent={} lowConfidence={} message='{}'",
                request.userId(), primaryIntent, intent.isLowConfidence(), request.message());

        // D29: 置信度不足 → 主动引导，不调用 LLM
        AgentReply clarifyReply = clarificationService.buildClarifyReply(intent);
        if (clarifyReply != null) {
            String encoded = replyService.encode(clarifyReply);
            Message assistantMessage = conversationService.saveMessage(
                    conversation.getId(), "assistant", encoded);
            log.info("[Agent] clarify userId={} options={}",
                    request.userId(),
                    clarifyReply.getCards() == null ? 0 : clarifyReply.getCards().size());
            AgentResponse clarificationResponse = AgentResponse.builder()
                    .conversationId(conversation.getId())
                    .messageId(assistantMessage.getId())
                    .content(clarifyReply.getText())
                    .cards(clarifyReply.getCards())
                    .intent("clarification")
                    .provider("system")
                    .model("intent-tree")
                    .totalTokens(0)
                    .degraded(false)
                    .build();
            idempotencyService.remember(idemLease, requestFingerprint, clarificationResponse);
            return clarificationResponse;
        }

        AgenticRagResult agenticRag = executeAgenticRag(
                primaryIntent, request, buildAgenticHistory(history));
        if (agenticRag != null && agenticRag.ragRequired()) {
            List<AgentReply.Card> citationCards = buildCitationCards(agenticRag.citations());
            AgentReply groundedReply = replyService.buildFallbackReply(
                    agenticRag.answer(), primaryIntent, citationCards, List.of());
            String encoded = replyService.encode(groundedReply);
            Message assistantMessage = conversationService.saveMessage(
                    conversation.getId(), "assistant", encoded);
            if (agenticRag.totalTokens() > 0) {
                usageRecorder.record(conversation.getId(), assistantMessage.getId(),
                        toProviderResponse(agenticRag), "agent",
                        agenticRag.latencyMs(), agenticRag.degraded());
            }
            AgentResponse groundedResponse = AgentResponse.builder()
                    .conversationId(conversation.getId())
                    .messageId(assistantMessage.getId())
                    .content(agenticRag.answer())
                    .cards(citationCards)
                    .intent(primaryIntent)
                    .provider(agenticRag.provider())
                    .model(agenticRag.model())
                    .totalTokens(agenticRag.totalTokens())
                    .degraded(agenticRag.degraded())
                    .build();
            idempotencyService.remember(idemLease, requestFingerprint, groundedResponse);
            return groundedResponse;
        }

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ToolCallResult> toolCalls = simulateToolCalls(primaryIntent, request);
        if (!toolCalls.isEmpty()) {
            toolResultCollector.addAll(toolCalls);
        }

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message(),
                request.preferredProvider(), agenticRag == null);

        long agentStart = System.currentTimeMillis();
        FailoverResult failoverResult;
        try {
            failoverResult = modelProviderRouter.executeWithFailover(
                    new ProviderChatRequest(request.model(), providerMessages));
        } catch (RuntimeException e) {
            // 全部 Provider 失败：补记一条 FAILED 用量，保证用量表能追溯彻底失败的请求；不吞异常
            usageRecorder.recordFailure(conversation.getId(),
                    failedProviderName(request.preferredProvider()), request.model(),
                    "agent", System.currentTimeMillis() - agentStart, e.getMessage());
            throw e;
        }
        var providerResponse = failoverResult.response();
        String modelText = providerResponse.content();

        AgentReply reply;
        AgentReply fallback = fallbackHandler.fallbackIfNeeded(
                request.message(), modelText, primaryIntent, toolResultCollector.getAll());
        if (fallback != null) {
            reply = fallback;
        } else {
            reply = replyService.buildReply(modelText, primaryIntent, failoverResult.degraded(),
                    toolResultCollector.getAll());
        }

        String encodedContent = replyService.encode(reply);
        Message assistantMessage = conversationService.saveMessage(
                conversation.getId(), "assistant", encodedContent);

        usageRecorder.record(
                conversation.getId(),
                assistantMessage.getId(),
                providerResponse,
                "agent",
                failoverResult.latencyMs(),
                failoverResult.degraded());

        log.info("[Agent] done intent={} provider={} cards={} degraded={}",
                primaryIntent, providerResponse.provider(),
                reply.getCards() == null ? 0 : reply.getCards().size(),
                failoverResult.degraded());

        AgentResponse response = AgentResponse.builder()
                .conversationId(conversation.getId())
                .messageId(assistantMessage.getId())
                .content(reply.getText())
                .cards(reply.getCards())
                .intent(primaryIntent)
                .provider(providerResponse.provider())
                .model(providerResponse.model())
                .totalTokens(providerResponse.totalTokens())
                .degraded(failoverResult.degraded())
                .build();

        idempotencyService.remember(idemLease, requestFingerprint, response);
        return response;
        } catch (Exception e) {
            idempotencyService.release(idemLease);
            throw e;
        }
    }

    // ==================== D15+D29: 流式 Agent ====================

    @Override
    public AgentStreamResult streamAgent(AgentRequest request,
                                          Consumer<String> onToken,
                                          Consumer<String> onCard) {
        // 安全检查：长度/频率/敏感词/Prompt注入
        String rejectReason = safetyAdvisor.check(request.userId(), request.message());
        if (rejectReason != null) {
            throw new BusinessException(rejectReason);
        }

        // 幂等快速路径：命中缓存 → 重放首次内容 + 卡片，不重新调用 LLM
        String idemNamespace = request.approvalId() == null || request.approvalId().isBlank()
                ? "agent-stream" : "agent-stream-approved";
        String requestFingerprint = idempotencyService.fingerprint(idemNamespace, request);
        Optional<AgentStreamResult> idemCached = idempotencyService.resolve(
                idemNamespace, request.userId(), request.idempotencyKey(), AgentStreamResult.class,
                requestFingerprint);
        if (idemCached.isPresent()) {
            AgentStreamResult cached = idemCached.get();
            if (cached.getContent() != null && !cached.getContent().isEmpty()) {
                onToken.accept(cached.getContent());
            }
            if (cached.getCards() != null && !cached.getCards().isEmpty()) {
                try {
                    onCard.accept(objectMapper.writeValueAsString(cached.getCards()));
                } catch (Exception e) {
                    log.warn("[Idempotency] replay card failed: {}", e.getMessage());
                }
            }
            log.info("[Idempotency] streamAgent replay cached key={}", request.idempotencyKey());
            return cached;
        }

        IdempotentRequestCache.IdempotencyLease idemLease = idempotencyService.acquire(
                idemNamespace, request.userId(), request.idempotencyKey(), requestFingerprint, 300);
        if (!idemLease.acquired() && idemLease.enabled()) {
            Optional<AgentStreamResult> waited = idempotencyService.resolve(
                    idemNamespace, request.userId(), request.idempotencyKey(), AgentStreamResult.class,
                    requestFingerprint);
            if (waited.isPresent()) {
                AgentStreamResult cached = waited.get();
                if (cached.getContent() != null && !cached.getContent().isEmpty()) {
                    onToken.accept(cached.getContent());
                }
                if (cached.getCards() != null && !cached.getCards().isEmpty()) {
                    try {
                        onCard.accept(objectMapper.writeValueAsString(cached.getCards()));
                    } catch (Exception e) {
                        log.warn("[StreamAgent] replay card failed: {}", e.getMessage());
                    }
                }
                return cached;
            }
            throw new BusinessException("幂等处理超时，请稍后重试");
        }

        try {

        // prototype scope：每次调用获取一个全新实例，线程安全，无 request 上下文依赖
        ToolResultCollector toolResultCollector = toolResultCollectorProvider.getObject();

        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        List<Message> history = conversationService.listMessages(conversation.getId());

        AgentIntent intent = intentAnalyzer.analyze(request.message());
        String primaryIntent = intent.getPrimary();
        log.info("[StreamAgent] userId={} intent={} lowConfidence={}",
                request.userId(), primaryIntent, intent.isLowConfidence());

        // D29: 置信度不足 → 主动引导
        AgentReply clarifyReply = clarificationService.buildClarifyReply(intent);
        if (clarifyReply != null) {
            String clarifyText = clarifyReply.getText();
            onToken.accept(clarifyText);
            if (clarifyReply.getCards() != null && !clarifyReply.getCards().isEmpty()) {
                try {
                    // P0-3 修复：使用 Jackson 序列化为合法 JSON，替代 toString()
                    String cardJson = objectMapper.writeValueAsString(clarifyReply.getCards());
                    onCard.accept(cardJson);
                } catch (Exception e) {
                    log.warn("[StreamAgent] clarify card failed: {}", e.getMessage());
                }
            }
            String encoded = replyService.encode(clarifyReply);
            Message assistantMessage = conversationService.saveMessage(
                    conversation.getId(), "assistant", encoded);
            AgentStreamResult clarificationResult = AgentStreamResult.builder()
                    .conversationId(conversation.getId())
                    .messageId(assistantMessage.getId())
                    .content(clarifyText)
                    .cards(clarifyReply.getCards())
                    .intent("clarification")
                    .model("intent-tree")
                    .provider("system")
                    .totalTokens(0)
                    .degraded(false)
                    .build();
            idempotencyService.remember(idemLease, requestFingerprint, clarificationResult);
            return clarificationResult;
        }

        AgenticRagResult agenticRag = executeAgenticRag(
                primaryIntent, request, buildAgenticHistory(history));
        if (agenticRag != null && agenticRag.ragRequired()) {
            List<AgentReply.Card> citationCards = buildCitationCards(agenticRag.citations());
            onToken.accept(agenticRag.answer());
            if (!citationCards.isEmpty()) {
                try {
                    onCard.accept(objectMapper.writeValueAsString(citationCards));
                } catch (Exception e) {
                    log.warn("[AgenticRAG] stream citation card failed: {}", e.getMessage());
                }
            }
            AgentReply groundedReply = replyService.buildFallbackReply(
                    agenticRag.answer(), primaryIntent, citationCards, List.of());
            ProviderChatResponse providerResponse = toProviderResponse(agenticRag);
            Message assistantMessage = assistantCompletionService.saveCompletion(
                    conversation.getId(), replyService.encode(groundedReply), providerResponse,
                    "agent", agenticRag.latencyMs(), agenticRag.degraded());
            AgentStreamResult groundedResult = AgentStreamResult.builder()
                    .conversationId(conversation.getId())
                    .messageId(assistantMessage.getId())
                    .content(agenticRag.answer())
                    .cards(citationCards)
                    .intent(primaryIntent)
                    .model(agenticRag.model())
                    .provider(agenticRag.provider())
                    .totalTokens(agenticRag.totalTokens())
                    .degraded(agenticRag.degraded())
                    .build();
            idempotencyService.remember(idemLease, requestFingerprint, groundedResult);
            return groundedResult;
        }

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ToolCallResult> toolCalls = simulateToolCalls(primaryIntent, request);
        if (!toolCalls.isEmpty()) {
            toolResultCollector.addAll(toolCalls);
        }

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message(),
                request.preferredProvider(), agenticRag == null);

        StringBuilder fullContent = new StringBuilder();
        Consumer<String> trackingOnToken = token -> {
            fullContent.append(token);
            onToken.accept(token);
        };

        ProviderChatRequest providerRequest = new ProviderChatRequest(request.model(), providerMessages);
        long agentStreamStart = System.currentTimeMillis();
        StreamResult streamResult;
        try {
            streamResult = modelProviderRouter.streamChatWithFailover(providerRequest, trackingOnToken);
        } catch (RuntimeException e) {
            // 全部 Provider 失败：补记一条 FAILED 用量；不吞异常
            usageRecorder.recordFailure(conversation.getId(),
                    failedProviderName(request.preferredProvider()), request.model(),
                    "agent", System.currentTimeMillis() - agentStreamStart, e.getMessage());
            throw e;
        }

        String modelText = fullContent.toString();
        AgentReply reply;
        AgentReply fallback = fallbackHandler.fallbackIfNeeded(
                request.message(), modelText, primaryIntent, toolResultCollector.getAll());
        if (fallback != null) {
            reply = fallback;
        } else {
            reply = replyService.buildReply(modelText, primaryIntent, false,
                    toolResultCollector.getAll());
        }

        if (reply.getCards() != null && !reply.getCards().isEmpty()) {
            try {
                // P0-3 修复：使用 Jackson 序列化为合法 JSON，替代 toString()
                String cardJson = objectMapper.writeValueAsString(reply.getCards());
                onCard.accept(cardJson);
            } catch (Exception e) {
                log.warn("[StreamAgent] send card failed: {}", e.getMessage());
            }
        }

        String encodedContent = replyService.encode(reply);

        ProviderChatResponse providerResponse = new ProviderChatResponse(
                modelText, streamResult.model(), streamResult.provider(),
                streamResult.promptTokens(), streamResult.completionTokens(), streamResult.totalTokens());

        Message assistantMessage = assistantCompletionService.saveCompletion(
                conversation.getId(), encodedContent, providerResponse, "agent",
                System.currentTimeMillis() - agentStreamStart, false);

        log.info("[StreamAgent] done intent={} provider={} cards={} tokens={}",
                primaryIntent, streamResult.provider(),
                reply.getCards() == null ? 0 : reply.getCards().size(),
                streamResult.totalTokens());

        AgentStreamResult result = AgentStreamResult.builder()
                .conversationId(conversation.getId())
                .messageId(assistantMessage.getId())
                .content(modelText)
                .cards(reply.getCards())
                .intent(primaryIntent)
                .model(streamResult.model())
                .provider(streamResult.provider())
                .totalTokens(streamResult.totalTokens())
                .degraded(false)
                .build();

        idempotencyService.remember(idemLease, requestFingerprint, result);
        return result;
        } catch (Exception e) {
            idempotencyService.release(idemLease);
            throw e;
        }
    }

    // ==================== 私有方法 ====================

    /** 全部 Provider 失败时没有实际命中的 Provider，用首选名兜底，缺省记为 none */
    private static String failedProviderName(String preferred) {
        return preferred != null && !preferred.isBlank() ? preferred : "none";
    }

    private AgenticRagResult executeAgenticRag(
            String primaryIntent, AgentRequest request, String historyContext) {
        if (!agenticRagEnabled || agenticRagOrchestrator == null
                || !AgentIntent.RAG.equals(primaryIntent)) {
            return null;
        }
        return agenticRagOrchestrator.execute(new AgenticRagRequest(
                request.message(), historyContext, request.preferredProvider(), request.model()));
    }

    private String buildAgenticHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            Message message = history.get(i);
            String content = message.getContent();
            if ("assistant".equalsIgnoreCase(message.getRole())) {
                content = replyService.decode(content).getText();
            }
            if (content != null && !content.isBlank()) {
                if (content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                context.append(message.getRole()).append("：").append(content).append('\n');
            }
        }
        return context.isEmpty() ? null : context.toString();
    }

    private static List<AgentReply.Card> buildCitationCards(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream().map(citation -> {
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("证据ID", citation.evidenceId());
            fields.put("片段ID", String.valueOf(citation.chunkId()));
            if (citation.documentId() != null) {
                fields.put("文档ID", String.valueOf(citation.documentId()));
            }
            fields.put("综合分", String.format(java.util.Locale.ROOT, "%.4f", citation.score()));
            return AgentReply.Card.builder()
                    .type("rag")
                    .title(citation.title() == null ? "知识片段" : citation.title())
                    .sourceId(citation.sourceId() == null
                            ? "rag:" + citation.chunkId() : citation.sourceId())
                    .fields(fields)
                    .build();
        }).toList();
    }

    private static ProviderChatResponse toProviderResponse(AgenticRagResult result) {
        return new ProviderChatResponse(result.answer(), result.model(), result.provider(),
                result.promptTokens(), result.completionTokens(), result.totalTokens());
    }


    private List<ToolCallResult> simulateToolCalls(String intent, AgentRequest request) {
        // P1-6 修复：Mock 工具服务未注入时直接返回空列表
        if (request.chatOnly() || (opsAgentToolService == null && reliableToolExecutor == null)) {
            return List.of();
        }
        String message = request.message();
        List<ToolCallResult> results = new ArrayList<>();
        switch (intent) {
            case AgentIntent.FAULT -> {
                List<ToolInvocation> invocations = List.of(
                        new ToolInvocation("queryServerStatus",
                                Map.of("hostname", extractHostname(message))),
                        new ToolInvocation("queryMetrics",
                                Map.of("service", extractHostname(message), "metric", "error_rate", "duration", "5m")));
                if (reliableToolExecutor != null) {
                    results.addAll(reliableToolExecutor.executeReadOnlyBatch(request.userId(), invocations));
                } else {
                    invocations.forEach(invocation -> results.add(opsAgentToolService.execute(
                            invocation.toolName(), invocation.params())));
                }
            }
            case AgentIntent.LOG -> {
                results.add(executeTool(request, "searchLogs",
                        Map.of("service", extractService(message), "keyword", "ERROR", "minutes", 30)));
            }
            case AgentIntent.DEPLOY -> {
                results.add(executeTool(request, "queryDeployHistory",
                        Map.of("service", extractService(message))));
            }
            case AgentIntent.TICKET -> {
                results.add(executeTool(request, "createTicket",
                        Map.of("title", "Agent 自动创建: " + message,
                                "description", message, "priority", "P2")));
            }
            default -> { /* RAG */ }
        }
        return results;
    }

    private ToolCallResult executeTool(AgentRequest request, String toolName, Map<String, Object> params) {
        if (reliableToolExecutor != null) {
            return reliableToolExecutor.execute(request.userId(), request.approvalId(), toolName, params);
        }
        return opsAgentToolService.execute(toolName, params);
    }

    private List<ProviderChatMessage> buildMessages(
            String systemPrompt, List<Message> history,
            String toolContext, String userMessage, String preferredProvider,
            boolean applyLegacyRag) {
        List<ProviderChatMessage> messages = new ArrayList<>();
        StringBuilder fullSystem = new StringBuilder(systemPrompt);
        if (toolContext != null && !toolContext.isBlank()) {
            fullSystem.append("\n\n").append(toolContext);
        }
        messages.add(new ProviderChatMessage("system", fullSystem.toString()));
        int start = Math.max(0, history.size() - 20);
        for (int i = start; i < history.size(); i++) {
            Message m = history.get(i);
            messages.add(new ProviderChatMessage(m.getRole(), m.getContent()));
        }
        if (applyLegacyRag) {
            messages = ragMessageAugmentor.augmentIfEnabled(messages, preferredProvider);
        }
        return messages;
    }

    private String extractHostname(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+\\.\\d+|[a-zA-Z][a-zA-Z0-9-]*\\.[a-zA-Z0-9-.]+|[a-zA-Z][a-zA-Z0-9-]{2,})")
                .matcher(message);
        return m.find() ? m.group(1) : "web-server-01";
    }

    private String extractService(String message) {
        return extractHostname(message);
    }
}

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
import com.zihan.zhiwei.ai.reply.AgentClarificationService;
import com.zihan.zhiwei.ai.reply.AgentFallbackHandler;
import com.zihan.zhiwei.ai.reply.AgentReply;
import com.zihan.zhiwei.ai.reply.AgentReplyService;
import com.zihan.zhiwei.ai.safety.SpringAiSafetyAdvisor;
import com.zihan.zhiwei.ai.stream.AgentStreamResult;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.AgentService;
import com.zihan.zhiwei.service.ConversationService;
import com.zihan.zhiwei.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final IdempotencyService idempotencyService;

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

        // 幂等快速路径：同一 idempotencyKey 已处理过 → 直接返回首次结果，不重复调用 LLM
        Optional<AgentResponse> idemCached = idempotencyService.resolve(
                request.userId(), request.idempotencyKey(), AgentResponse.class);
        if (idemCached.isPresent()) {
            return idemCached.get();
        }

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
            return AgentResponse.builder()
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
        }

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ToolCallResult> toolCalls = simulateToolCalls(primaryIntent, request.message());
        if (!toolCalls.isEmpty()) {
            toolResultCollector.addAll(toolCalls);
        }

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message(), request.preferredProvider());

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
                request.message(), modelText, primaryIntent);
        if (fallback != null) {
            reply = fallback;
        } else {
            reply = replyService.buildReply(modelText, primaryIntent, failoverResult.degraded());
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

        // 幂等记录：缓存首次成功结果，重试命中直接返回
        idempotencyService.remember(request.userId(), request.idempotencyKey(), response);
        return response;
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
        Optional<AgentStreamResult> idemCached = idempotencyService.resolve(
                request.userId(), request.idempotencyKey(), AgentStreamResult.class);
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
            return AgentStreamResult.builder()
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
        }

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ToolCallResult> toolCalls = simulateToolCalls(primaryIntent, request.message());
        if (!toolCalls.isEmpty()) {
            toolResultCollector.addAll(toolCalls);
        }

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message(), request.preferredProvider());

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
        AgentReply fallback = fallbackHandler.fallbackIfNeeded(request.message(), modelText, primaryIntent);
        if (fallback != null) {
            reply = fallback;
        } else {
            reply = replyService.buildReply(modelText, primaryIntent, false);
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

        // P2-12 修复：将助手消息保存 + usage 记录抽为事务原子方法
        Message assistantMessage = saveStreamCompletion(
                conversation.getId(), encodedContent, providerResponse, 0L, false);

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

        // 幂等记录：缓存首次流式结果（含完整文本 + 卡片），重试时重放
        idempotencyService.remember(request.userId(), request.idempotencyKey(), result);
        return result;
    }

    // ==================== 私有方法 ====================

    /** 全部 Provider 失败时没有实际命中的 Provider，用首选名兜底，缺省记为 none */
    private static String failedProviderName(String preferred) {
        return preferred != null && !preferred.isBlank() ? preferred : "none";
    }

    /**
     * P2-12 修复：流式完成后，在事务中原子地保存助手消息 + 记录 usage。
     * 不与长时间的 SSE 流转共享事务，避免长事务锁表。
     */
    @Transactional
    private Message saveStreamCompletion(Long conversationId, String content,
                                          ProviderChatResponse providerResponse,
                                          long latencyMs, boolean degraded) {
        Message assistantMessage = conversationService.saveMessage(
                conversationId, "assistant", content);
        usageRecorder.record(conversationId, assistantMessage.getId(),
                providerResponse, "agent", latencyMs, degraded);
        return assistantMessage;
    }

    private List<ToolCallResult> simulateToolCalls(String intent, String message) {
        // P1-6 修复：Mock 工具服务未注入时直接返回空列表
        if (opsAgentToolService == null) {
            return List.of();
        }
        List<ToolCallResult> results = new ArrayList<>();
        switch (intent) {
            case AgentIntent.FAULT -> {
                results.add(opsAgentToolService.execute("queryServerStatus",
                        Map.of("hostname", extractHostname(message))));
                results.add(opsAgentToolService.execute("queryMetrics",
                        Map.of("service", extractHostname(message), "metric", "error_rate", "duration", "5m")));
            }
            case AgentIntent.LOG -> {
                results.add(opsAgentToolService.execute("searchLogs",
                        Map.of("service", extractService(message), "keyword", "ERROR", "minutes", 30)));
            }
            case AgentIntent.DEPLOY -> {
                results.add(opsAgentToolService.execute("queryDeployHistory",
                        Map.of("service", extractService(message))));
            }
            case AgentIntent.TICKET -> {
                results.add(opsAgentToolService.execute("createTicket",
                        Map.of("title", "Agent 自动创建: " + message,
                                "description", message, "priority", "P2")));
            }
            default -> { /* RAG */ }
        }
        return results;
    }

    private List<ProviderChatMessage> buildMessages(
            String systemPrompt, List<Message> history,
            String toolContext, String userMessage, String preferredProvider) {
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
        messages = ragMessageAugmentor.augmentIfEnabled(messages, preferredProvider);
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

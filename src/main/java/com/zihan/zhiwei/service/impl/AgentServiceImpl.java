package com.zihan.zhiwei.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.intent.AgentIntentAnalyzer;
import com.zihan.zhiwei.ai.prompt.AiPromptService;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.dto.ToolCall;
import com.zihan.zhiwei.ai.provider.dto.ToolDefinition;
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
import com.zihan.zhiwei.service.IdempotentRequestCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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

    private static final int MAX_TOOL_ROUNDS = 4;

    private final ConversationService conversationService;
    private final ModelProviderRouter modelProviderRouter;
    private final UsageRecorder usageRecorder;
    private final AgentIntentAnalyzer intentAnalyzer;
    private final AiPromptService promptService;
    private final RagMessageAugmentor ragMessageAugmentor;
    private final RagContextBuilder ragContextBuilder;
    /**
     * P1-6 修复：改为可选注入，Mock 服务未启用时（zhiwei.ai.tool.mock-enabled != true）
     * 不会影响应用启动；工具调用循环会处理工具服务未启用的情况。
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
    private final IdempotentRequestCache idempotencyService;

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
        String idemNamespace = "agent";
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

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, "", request.message(), request.preferredProvider());

        long agentStart = System.currentTimeMillis();
        FailoverResult failoverResult = null;
        ProviderChatResponse providerResponse = null;
        try {
            List<ToolDefinition> tools = toolDefinitions(request.chatOnly());
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                failoverResult = modelProviderRouter.executeWithFailover(
                        new ProviderChatRequest(request.model(), providerMessages, tools,
                                tools.isEmpty() ? null : "auto"));
                providerResponse = failoverResult.response();
                if (!providerResponse.hasToolCalls()) break;
                executeToolCalls(providerMessages, providerResponse, toolResultCollector);
                if (round == MAX_TOOL_ROUNDS - 1) throw new BusinessException("工具调用超过最大轮数");
            }
        } catch (RuntimeException e) {
            // 全部 Provider 失败：补记一条 FAILED 用量，保证用量表能追溯彻底失败的请求；不吞异常
            usageRecorder.recordFailure(conversation.getId(),
                    failedProviderName(request.preferredProvider()), request.model(),
                    "agent", System.currentTimeMillis() - agentStart, e.getMessage());
            throw e;
        }
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
        String idemNamespace = "agent-stream";
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

        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, "", request.message(), request.preferredProvider());

        StringBuilder fullContent = new StringBuilder();
        long agentStreamStart = System.currentTimeMillis();
        StreamResult streamResult = null;
        ProviderChatResponse providerResponse = null;
        try {
            List<ToolDefinition> tools = toolDefinitions(request.chatOnly());
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                StringBuilder roundContent = new StringBuilder();
                streamResult = modelProviderRouter.streamChatWithFailover(
                        new ProviderChatRequest(request.model(), providerMessages, tools,
                                tools.isEmpty() ? null : "auto"), roundContent::append);
                if (streamResult.toolCalls() == null || streamResult.toolCalls().isEmpty()) {
                    fullContent.append(roundContent);
                    if (!roundContent.isEmpty()) onToken.accept(roundContent.toString());
                    providerResponse = new ProviderChatResponse(fullContent.toString(), streamResult.model(),
                            streamResult.provider(), streamResult.promptTokens(), streamResult.completionTokens(),
                            streamResult.totalTokens());
                    break;
                }
                providerResponse = new ProviderChatResponse(roundContent.toString(), streamResult.model(),
                        streamResult.provider(), streamResult.promptTokens(), streamResult.completionTokens(),
                        streamResult.totalTokens(), streamResult.toolCalls());
                executeToolCalls(providerMessages, providerResponse, toolResultCollector);
                if (round == MAX_TOOL_ROUNDS - 1) throw new BusinessException("工具调用超过最大轮数");
            }
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

    private List<ToolDefinition> toolDefinitions(boolean chatOnly) {
        if (chatOnly || opsAgentToolService == null) return List.of();
        List<Map<String, Object>> definitions = opsAgentToolService.toolDefinitions();
        if (definitions == null || definitions.isEmpty()) return List.of();
        return definitions.stream().map(definition -> new ToolDefinition(
                (String) definition.get("name"),
                (String) definition.get("description"),
                castMap(definition.get("parameters")))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) (Map<?, ?>) map : Map.of();
    }

    private void executeToolCalls(List<ProviderChatMessage> messages,
                                  ProviderChatResponse response,
                                  ToolResultCollector collector) {
        messages.add(ProviderChatMessage.assistantToolCalls(response.content(), response.toolCalls()));
        for (ToolCall call : response.toolCalls()) {
            if (opsAgentToolService == null) {
                messages.add(ProviderChatMessage.toolResult(call.id(), call.name(), "工具服务未启用，无法执行该工具"));
                continue;
            }
            Map<String, Object> arguments;
            try {
                arguments = objectMapper.readValue(call.arguments(), Map.class);
            } catch (Exception e) {
                arguments = new HashMap<>();
            }
            ToolCallResult result = opsAgentToolService.execute(call.name(), arguments);
            collector.add(result);
            String text = result.isSuccess() ? result.getData() : "工具执行失败: " + result.getError();
            messages.add(ProviderChatMessage.toolResult(call.id(), call.name(), text));
        }
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

}

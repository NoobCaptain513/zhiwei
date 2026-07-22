package com.zihan.zhiwei.service.impl;

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
import com.zihan.zhiwei.ai.reply.AgentFallbackHandler;
import com.zihan.zhiwei.ai.reply.AgentReply;
import com.zihan.zhiwei.ai.reply.AgentReplyService;
import com.zihan.zhiwei.ai.stream.AgentStreamResult;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.tool.OpsAgentToolService;
import com.zihan.zhiwei.ai.tool.ToolCallResult;
import com.zihan.zhiwei.ai.tool.ToolResultCollector;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.AgentService;
import com.zihan.zhiwei.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * D14+D15: Agent 全链路实现。
 * D15: 新增 streamAgent() 流式版本。
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
    private final OpsAgentToolService opsAgentToolService;
    private final ToolResultCollector toolResultCollector;
    private final AgentFallbackHandler fallbackHandler;
    private final AgentReplyService replyService;

    // ==================== D14: 同步 Agent（保持不变）====================

    @Override
    @Transactional
    public AgentResponse agent(AgentRequest request) {
        toolResultCollector.clear();

        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        List<Message> history = conversationService.listMessages(conversation.getId());

        AgentIntent intent = intentAnalyzer.analyze(request.message());
        String primaryIntent = intent.getPrimary();
        log.info("[Agent] userId={} intent={} message='{}'",
                request.userId(), primaryIntent, request.message());

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
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message());

        FailoverResult failoverResult = modelProviderRouter.executeWithFailover(
                new ProviderChatRequest(request.model(), providerMessages));
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

        return AgentResponse.builder()
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
    }

    // ==================== D15: 流式 Agent ====================

    /**
     * D15: Agent 全链路流式版。
     * 流程：意图 → 工具 → 构建 prompt → 流式调用 → 卡片组装 → 入库 → usage。
     */
    @Override
    public AgentStreamResult streamAgent(AgentRequest request,
                                         Consumer<String> onToken,
                                         Consumer<String> onCard) {
        // 0. 清空上次工具收集
        toolResultCollector.clear();

        // 1. 会话管理
        Conversation conversation = conversationService.getOrCreate(
                request.userId(), request.conversationId());
        conversationService.saveMessage(conversation.getId(), "user", request.message());

        List<Message> history = conversationService.listMessages(conversation.getId());

        // 2. 意图识别
        AgentIntent intent = intentAnalyzer.analyze(request.message());
        String primaryIntent = intent.getPrimary();
        log.info("[StreamAgent] userId={} intent={}", request.userId(), primaryIntent);

        // 3. 构建 system prompt
        String systemPrompt = promptService.buildSystemPrompt(primaryIntent, Map.of(
                "user", request.userId(),
                "time", java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        ));

        // 4. 模拟工具调用
        List<ToolCallResult> toolCalls = simulateToolCalls(primaryIntent, request.message());
        if (!toolCalls.isEmpty()) {
            toolResultCollector.addAll(toolCalls);
        }

        // 5. 构建消息列表
        List<ProviderChatMessage> providerMessages = buildMessages(
                systemPrompt, history, toolResultCollector.toContextBlock(), request.message());

        // 6. 流式调用（收集完整文本）
        StringBuilder fullContent = new StringBuilder();
        Consumer<String> trackingOnToken = token -> {
            fullContent.append(token);
            onToken.accept(token);
        };

        ProviderChatRequest providerRequest = new ProviderChatRequest(request.model(), providerMessages);
        StreamResult streamResult = modelProviderRouter.streamChatWithFailover(providerRequest, trackingOnToken);

        // 7. 卡片组装 + 兜底
        String modelText = fullContent.toString();
        AgentReply reply;
        AgentReply fallback = fallbackHandler.fallbackIfNeeded(request.message(), modelText, primaryIntent);
        if (fallback != null) {
            reply = fallback;
        } else {
            reply = replyService.buildReply(modelText, primaryIntent, false);
        }

        // 8. 发送卡片
        if (reply.getCards() != null && !reply.getCards().isEmpty()) {
            try {
                String cardJson = Result.ok(reply.getCards()).toString();
                onCard.accept(cardJson);
            } catch (Exception e) {
                log.warn("[StreamAgent] send card failed: {}", e.getMessage());
            }
        }

        // 9. 保存助手消息 + 记录 usage
        String encodedContent = replyService.encode(reply);
        Message assistantMessage = conversationService.saveMessage(
                conversation.getId(), "assistant", encodedContent);

        ProviderChatResponse providerResponse = new ProviderChatResponse(
                modelText, streamResult.model(), streamResult.provider(),
                streamResult.promptTokens(), streamResult.completionTokens(), streamResult.totalTokens());
        usageRecorder.record(conversation.getId(), assistantMessage.getId(),
                providerResponse, "agent", 0L, false);

        log.info("[StreamAgent] done intent={} provider={} cards={} tokens={}",
                primaryIntent, streamResult.provider(),
                reply.getCards() == null ? 0 : reply.getCards().size(),
                streamResult.totalTokens());

        return AgentStreamResult.builder()
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
    }

    // ==================== 私有方法（保持不变）====================

    private List<ToolCallResult> simulateToolCalls(String intent, String message) {
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
            String toolContext, String userMessage) {
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
        messages = ragMessageAugmentor.augmentIfEnabled(messages);
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
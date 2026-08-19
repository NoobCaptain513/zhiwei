package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.rag.RagMessageAugmentor;
import com.zihan.zhiwei.ai.safety.SpringAiSafetyAdvisor;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.pojo.dto.ChatRequest;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ChatService;
import com.zihan.zhiwei.service.ConversationService;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationService conversationService;
    private final ModelProviderRouter modelProviderRouter;
    private final UsageRecorder usageRecorder;
    private final RagMessageAugmentor ragMessageAugmentor;
    private final SpringAiSafetyAdvisor safetyAdvisor;
    private final IdempotentRequestCache idempotencyService;

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        // 安全检查：长度/频率/敏感词/Prompt注入
        String rejectReason = safetyAdvisor.check(request.userId(), request.message());
        if (rejectReason != null) {
            throw new BusinessException(rejectReason);
        }

        // 幂等快速路径：同一 namespace + idempotencyKey 已处理过 → 直接返回首次结果
        String idemNamespace = "chat";
        String requestFingerprint = idempotencyService.fingerprint(idemNamespace, request);
        Optional<ChatResponse> idemCached = idempotencyService.resolve(
                idemNamespace, request.userId(), request.idempotencyKey(), ChatResponse.class, requestFingerprint);
        if (idemCached.isPresent()) {
            return idemCached.get();
        }

        IdempotentRequestCache.IdempotencyLease idemLease = idempotencyService.acquire(
                idemNamespace, request.userId(), request.idempotencyKey(), requestFingerprint, 300);
        if (!idemLease.acquired() && idemLease.enabled()) {
            Optional<ChatResponse> waited = idempotencyService.resolve(
                    idemNamespace, request.userId(), request.idempotencyKey(), ChatResponse.class, requestFingerprint);
            if (waited.isPresent()) {
                return waited.get();
            }
            throw new BusinessException("幂等处理超时，请稍后重试");
        }

        try {
            Conversation conversation = conversationService.getOrCreate(
                    request.userId(), request.conversationId());
            conversationService.saveMessage(conversation.getId(), "user", request.message());

            List<Message> history = conversationService.listMessages(conversation.getId());
            List<ProviderChatMessage> providerMessages = new ArrayList<>();
            int maxHistory = 20; // P2-17 修复：截断历史，防止超出模型 context 限制
            int start = Math.max(0, history.size() - maxHistory);
            for (int i = start; i < history.size(); i++) {
                providerMessages.add(new ProviderChatMessage(history.get(i).getRole(), history.get(i).getContent()));
            }
            providerMessages = ragMessageAugmentor.augmentIfEnabled(providerMessages, request.preferredProvider());

            long chatStart = System.currentTimeMillis();
            FailoverResult failoverResult;
            try {
                failoverResult = modelProviderRouter.executeWithFailover(
                        new ProviderChatRequest(request.model(), providerMessages));
            } catch (RuntimeException e) {
                // 全部 Provider 失败：补记一条 FAILED 用量，保证用量表能追溯彻底失败的请求；不吞异常
                usageRecorder.recordFailure(conversation.getId(),
                        failedProviderName(request.preferredProvider()), request.model(),
                        UsageRecorder.MODE_CHAT, System.currentTimeMillis() - chatStart, e.getMessage());
                idempotencyService.release(idemLease);
                throw e;
            }
            var providerResponse = failoverResult.response();

            Message assistantMessage = conversationService.saveMessage(
                    conversation.getId(), "assistant", providerResponse.content());

            usageRecorder.record(
                    conversation.getId(),
                    assistantMessage.getId(),
                    providerResponse,
                    UsageRecorder.MODE_CHAT,
                    failoverResult.latencyMs(),
                    failoverResult.degraded());

            ChatResponse response = new ChatResponse(
                    conversation.getId(),
                    assistantMessage.getId(),
                    providerResponse.content(),
                    providerResponse.model(),
                    providerResponse.provider(),
                    providerResponse.totalTokens()
            );
            idempotencyService.remember(idemLease, requestFingerprint, response);
            return response;
        } catch (Exception e) {
            idempotencyService.release(idemLease);
            throw e;
        }
    }

    /**
     * D15: 流式聊天。
     * 会话管理 + RAG + 流式路由；完整文本由 onToken 收集后入库。
     */
    @Override
    public StreamResult streamChat(ChatRequest request, Consumer<String> onToken) {
        // 安全检查：长度/频率/敏感词/Prompt注入
        String rejectReason = safetyAdvisor.check(request.userId(), request.message());
        if (rejectReason != null) {
            throw new BusinessException(rejectReason);
        }

        // 幂等快速路径：命中缓存 → 重放首次完整内容，不重新调用 LLM
        String idemNamespace = "chat-stream";
        String requestFingerprint = idempotencyService.fingerprint(idemNamespace, request);
        Optional<ChatResponse> idemCached = idempotencyService.resolve(
                idemNamespace, request.userId(), request.idempotencyKey(), ChatResponse.class,
                requestFingerprint);
        if (idemCached.isPresent()) {
            ChatResponse cached = idemCached.get();
            if (cached.content() != null && !cached.content().isEmpty()) {
                onToken.accept(cached.content());
            }
            log.info("[Idempotency] streamChat replay cached key={}", request.idempotencyKey());
            return StreamResult.of(cached.model(), cached.provider(), 0, cached.totalTokens());
        }

        IdempotentRequestCache.IdempotencyLease idemLease = idempotencyService.acquire(
                idemNamespace, request.userId(), request.idempotencyKey(), requestFingerprint, 300);
        if (!idemLease.acquired() && idemLease.enabled()) {
            Optional<ChatResponse> waited = idempotencyService.resolve(
                    idemNamespace, request.userId(), request.idempotencyKey(), ChatResponse.class,
                    requestFingerprint);
            if (waited.isPresent()) {
                ChatResponse cached = waited.get();
                if (cached.content() != null && !cached.content().isEmpty()) {
                    onToken.accept(cached.content());
                }
                return StreamResult.of(cached.model(), cached.provider(), 0, cached.totalTokens());
            }
            throw new BusinessException("幂等处理超时，请稍后重试");
        }

        try {
            // 1. 会话管理
            Conversation conversation = conversationService.getOrCreate(
                    request.userId(), request.conversationId());
            conversationService.saveMessage(conversation.getId(), "user", request.message());

            // 2. 加载历史 + RAG 增强
            List<Message> history = conversationService.listMessages(conversation.getId());
            List<ProviderChatMessage> providerMessages = new ArrayList<>();
            int maxHistory = 20; // P2-17 修复：截断历史，防止超出模型 context 限制
            int start = Math.max(0, history.size() - maxHistory);
            for (int i = start; i < history.size(); i++) {
                providerMessages.add(new ProviderChatMessage(history.get(i).getRole(), history.get(i).getContent()));
            }
            providerMessages = ragMessageAugmentor.augmentIfEnabled(providerMessages, request.preferredProvider());

            // 3. 收集完整文本（流式 + 入库）
            StringBuilder fullContent = new StringBuilder();
            Consumer<String> wrappedOnToken = token -> {
                fullContent.append(token);
                onToken.accept(token);
            };

            // 4. 路由 + 流式调用
            ProviderChatRequest providerRequest = new ProviderChatRequest(request.model(), providerMessages);
            long streamStart = System.currentTimeMillis();
            StreamResult streamResult;
            try {
                streamResult = modelProviderRouter.streamChatWithFailover(providerRequest, wrappedOnToken);
            } catch (RuntimeException e) {
                // 全部 Provider 失败：补记一条 FAILED 用量；不吞异常
                usageRecorder.recordFailure(conversation.getId(),
                        failedProviderName(request.preferredProvider()), request.model(),
                        UsageRecorder.MODE_CHAT, System.currentTimeMillis() - streamStart, e.getMessage());
                idempotencyService.release(idemLease);
                throw e;
            }

            // 5. 保存助手消息 + 记录 usage
            String content = fullContent.toString();
            ProviderChatResponse providerResponse = new ProviderChatResponse(
                    content, streamResult.model(), streamResult.provider(),
                    streamResult.promptTokens(), streamResult.completionTokens(), streamResult.totalTokens());

            // P2-12 修复：将最后的 DB 写入抽为 @Transactional 原子方法
            saveStreamCompletion(conversation.getId(), content, providerResponse, 0L, false);

            idempotencyService.remember(idemLease, requestFingerprint,
                    new ChatResponse(conversation.getId(), null, content,
                            providerResponse.model(), providerResponse.provider(),
                            providerResponse.totalTokens()));

            return streamResult;
        } catch (Exception e) {
            idempotencyService.release(idemLease);
            throw e;
        }
    }

    /** 全部 Provider 失败时没有实际命中的 Provider，用首选名兜底，缺省记为 none */
    private static String failedProviderName(String preferred) {
        return preferred != null && !preferred.isBlank() ? preferred : "none";
    }

    /**
     * P2-12 修复：流式完成后，在事务中原子地保存助手消息 + 记录 usage。
     * 不与长时间的 SSE 流转共享事务，避免长事务锁表。
     */
    @Transactional
    private void saveStreamCompletion(Long conversationId, String content,
                                       ProviderChatResponse providerResponse,
                                       long latencyMs, boolean degraded) {
        conversationService.saveMessage(conversationId, "assistant", content);
        usageRecorder.record(conversationId, null, providerResponse,
                UsageRecorder.MODE_CHAT, latencyMs, degraded);
    }
}

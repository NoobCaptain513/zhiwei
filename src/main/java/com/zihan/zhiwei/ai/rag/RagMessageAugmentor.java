package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D11+D31: 统一消息增强。
 * D31: 提取最近对话历史作为查询改写的上下文。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagMessageAugmentor {

    private final RagContextBuilder ragContextBuilder;

    @Value("${zhiwei.ai.rag.inject-on-chat:true}")
    private boolean injectOnChat;

    @Value("${zhiwei.ai.rag.rewrite-history-size:3}")
    private int rewriteHistorySize;

    /**
     * 消息增强（不带 Provider 参数，向后兼容）
     */
    public List<ProviderChatMessage> augmentIfEnabled(List<ProviderChatMessage> messages) {
        return augmentIfEnabled(messages, null);
    }

    /**
     * 消息增强（带 Provider 参数，根据 Provider 动态选择 Embedding）
     * @param messages 原始消息列表
     * @param provider Provider 名称（如 "ollama", "native-dashscope"），null 表示默认
     * @return 增强后的消息列表
     */
    public List<ProviderChatMessage> augmentIfEnabled(List<ProviderChatMessage> messages, String provider) {
        if (!injectOnChat || messages == null || messages.isEmpty()) {
            return messages;
        }

        String lastUser = null;
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) {
                lastUser = messages.get(i).content();
                lastUserIdx = i;
                break;
            }
        }
        if (lastUser == null || lastUser.isBlank()) {
            return messages;
        }

        // D31: 提取最近对话历史作为改写上下文
        String historyContext = buildHistoryContext(messages, lastUserIdx);

        String block = ragContextBuilder.buildContextBlock(lastUser.trim(), historyContext, provider);
        if (block == null || block.isBlank()) {
            return messages;
        }
        List<ProviderChatMessage> out = new ArrayList<>(messages.size() + 1);
        out.add(new ProviderChatMessage("system", block));
        out.addAll(messages);
        return out;
    }

    private String buildHistoryContext(List<ProviderChatMessage> messages, int currentIdx) {
        if (currentIdx <= 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int count = 0;
        for (int i = currentIdx - 1; i >= 0 && count < rewriteHistorySize; i--) {
            ProviderChatMessage msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.role()) || "assistant".equalsIgnoreCase(msg.role())) {
                String role = "user".equalsIgnoreCase(msg.role()) ? "用户" : "助手";
                String content = msg.content();
                if (content != null && !content.isBlank()) {
                    if (content.length() > 100) {
                        content = content.substring(0, 100) + "...";
                    }
                    parts.addFirst(role + "：" + content);
                    count++;
                }
            }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }
}

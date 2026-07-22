package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D11: 统一消息增强 —— 三个 Provider 共用，chat 前注入 RAG system。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagMessageAugmentor {

    private final RagContextBuilder ragContextBuilder;

    @Value("${zhiwei.ai.rag.inject-on-chat:true}")
    private boolean injectOnChat;

    public List<ProviderChatMessage> augmentIfEnabled(List<ProviderChatMessage> messages) {
        if (!injectOnChat || messages == null || messages.isEmpty()) {
            return messages;
        }
        String lastUser = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) {
                lastUser = messages.get(i).content();
                break;
            }
        }
        if (lastUser == null || lastUser.isBlank()) {
            return messages;
        }
        String block = ragContextBuilder.buildContextBlock(lastUser.trim());
        if (block == null || block.isBlank()) {
            return messages;
        }
        List<ProviderChatMessage> out = new ArrayList<>(messages.size() + 1);
        out.add(new ProviderChatMessage("system", block));
        out.addAll(messages);
        return out;
    }
}
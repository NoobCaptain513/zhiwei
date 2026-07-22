package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.stream.StreamResult;

import java.util.function.Consumer;

/**
 * AI 模型提供方抽象接口。
 * D15: 新增 streamChat() 流式方法，默认回退同步 chat()。
 */
public interface ModelProvider {

    /** Provider 唯一标识，如 spring-ai-alibaba */
    String name();

    /** 是否可用（第一周简单返回 true，后续接 HealthMonitor） */
    default boolean isAvailable() {
        return true;
    }

    /** 同步聊天 */
    ProviderChatResponse chat(ProviderChatRequest request);

    /**
     * 流式聊天：每产出一个 token 就调用 onToken。
     * 默认实现：回退到同步 chat()，一次性 emit 全部内容。
     * 子类可覆写以实现真正的 SSE 流式。
     *
     * @return 流结束后返回元数据（不含完整文本，调用方自行拼接）
     */
    default StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        ProviderChatResponse response = chat(request);
        if (response.content() != null && !response.content().isEmpty()) {
            onToken.accept(response.content());
        }
        return StreamResult.of(
                response.model(),
                response.provider(),
                response.promptTokens(),
                response.completionTokens()
        );
    }
}
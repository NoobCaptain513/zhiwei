package com.zihan.zhiwei.ai.memory;

/** A completed user/assistant turn whose message transaction committed. */
public record ConversationTurnCompletedEvent(
        String userId,
        long conversationId,
        long userMessageId,
        long assistantMessageId) {

    public ConversationTurnCompletedEvent {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (conversationId <= 0 || userMessageId <= 0 || assistantMessageId <= 0) {
            throw new IllegalArgumentException("conversation and message IDs must be positive");
        }
        if (assistantMessageId < userMessageId) {
            throw new IllegalArgumentException("assistantMessageId must not precede userMessageId");
        }
    }
}

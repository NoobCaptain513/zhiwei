package com.zihan.zhiwei.ai.memory;

public interface MemoryContextService {
    MemoryContext buildContext(String userId, long conversationId, String currentUserMessage, int tokenBudget);
}

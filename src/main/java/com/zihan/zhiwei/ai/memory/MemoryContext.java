package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.pojo.entity.Message;

import java.util.List;
import java.util.Optional;

/**
 * Structured, budgeted memory assembled for one request.
 * The current user message is kept separate from persisted, untrusted memory.
 */
public record MemoryContext(
        String currentUserMessage,
        Optional<MemorySummary> summary,
        Optional<AgentCheckpoint> checkpoint,
        List<MemoryFactService.FactView> facts,
        List<Message> recentMessages,
        int estimatedTokens,
        int tokenBudget) {

    public MemoryContext {
        currentUserMessage = currentUserMessage == null ? "" : currentUserMessage;
        summary = summary == null ? Optional.empty() : summary;
        checkpoint = checkpoint == null ? Optional.empty() : checkpoint;
        facts = facts == null ? List.of() : List.copyOf(facts);
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        if (tokenBudget < 0 || estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("memory context token estimate exceeds budget");
        }
    }
}

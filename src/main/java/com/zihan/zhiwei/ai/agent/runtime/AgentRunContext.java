package com.zihan.zhiwei.ai.agent.runtime;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;

import java.util.concurrent.atomic.AtomicInteger;

/** Explicit per-run context; safe to pass into virtual-thread work. */
public final class AgentRunContext {

    private final TokenBudget budget;
    private final AgentNodeObserver observer;
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();

    public AgentRunContext(TokenBudget budget, AgentNodeObserver observer) {
        this.budget = budget;
        this.observer = observer;
    }

    public TokenBudget.Reservation reserve(
            String node, int estimatedPromptTokens, int maxCompletionTokens, boolean critical) {
        return budget.reserve(node,
                Math.max(0, estimatedPromptTokens) + Math.max(0, maxCompletionTokens), critical);
    }

    public void commit(String node, TokenBudget.Reservation reservation, ProviderChatResponse response) {
        int prompt = response == null ? 0 : Math.max(0, response.promptTokens());
        int completion = response == null ? 0 : Math.max(0, response.completionTokens());
        reservation.commit(prompt, completion);
        promptTokens.addAndGet(prompt);
        completionTokens.addAndGet(completion);
        observer.recordTokens(node, response == null ? "unknown" : response.provider(), prompt, completion);
    }

    public <T> T observe(String node, java.util.function.Supplier<T> operation) {
        return observer.observe(node, operation);
    }

    public void recordFailure(String node) {
        observer.recordFailure(node);
    }

    public int promptTokens() { return promptTokens.get(); }
    public int completionTokens() { return completionTokens.get(); }
    public int totalTokens() { return promptTokens() + completionTokens(); }
    public int remainingTokens() { return budget.remainingTokens(); }
    public long remainingDurationMillis() { return budget.remainingDurationMillis(); }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) ascii++; else nonAscii++;
        }
        return nonAscii + (int) Math.ceil(ascii / 4.0);
    }
}

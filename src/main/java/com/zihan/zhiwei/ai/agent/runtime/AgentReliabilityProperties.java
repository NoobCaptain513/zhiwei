package com.zihan.zhiwei.ai.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "zhiwei.ai.agent.reliability")
public class AgentReliabilityProperties {

    private int maxTotalTokens = 12_000;
    private int reservedAnswerTokens = 1_500;
    private int maxNodeCalls = 12;
    private long maxDurationMs = 30_000;

    public TokenBudget newBudget() {
        return new TokenBudget(maxTotalTokens, reservedAnswerTokens, maxNodeCalls,
                Duration.ofMillis(Math.max(1L, maxDurationMs)));
    }

    public int getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(int value) { this.maxTotalTokens = value; }
    public int getReservedAnswerTokens() { return reservedAnswerTokens; }
    public void setReservedAnswerTokens(int value) { this.reservedAnswerTokens = value; }
    public int getMaxNodeCalls() { return maxNodeCalls; }
    public void setMaxNodeCalls(int value) { this.maxNodeCalls = value; }
    public long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(long value) { this.maxDurationMs = value; }
}

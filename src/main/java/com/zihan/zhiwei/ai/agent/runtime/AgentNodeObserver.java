package com.zihan.zhiwei.ai.agent.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Low-cardinality metrics for Agent nodes and tool executions. */
@Component
public class AgentNodeObserver {

    private final MeterRegistry registry;

    public AgentNodeObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T observe(String node, Supplier<T> operation) {
        registry.counter("zhiwei.agent.node.calls", "node", node).increment();
        Timer.Sample sample = Timer.start(registry);
        try {
            return operation.get();
        } catch (RuntimeException | Error e) {
            registry.counter("zhiwei.agent.node.failures", "node", node).increment();
            throw e;
        } finally {
            sample.stop(registry.timer("zhiwei.agent.node.duration", "node", node));
        }
    }

    public void recordTokens(String node, String provider, int promptTokens, int completionTokens) {
        String safeProvider = provider == null || provider.isBlank() ? "unknown" : provider;
        registry.counter("zhiwei.agent.node.tokens", "node", node, "type", "prompt", "provider", safeProvider)
                .increment(Math.max(0, promptTokens));
        registry.counter("zhiwei.agent.node.tokens", "node", node, "type", "completion", "provider", safeProvider)
                .increment(Math.max(0, completionTokens));
    }

    public void recordFailure(String node) {
        registry.counter("zhiwei.agent.node.failures", "node", node).increment();
    }
}

package com.zihan.zhiwei.ai.agent.runtime;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunContextTest {

    @Test
    void shouldAggregateActualUsageAndPublishNodeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(100, 20, 5, Duration.ofSeconds(5)),
                new AgentNodeObserver(registry));

        try (TokenBudget.Reservation reservation = context.reserve("classify", 10, 10, false)) {
            context.commit("classify", reservation,
                    new ProviderChatResponse("{}", "m", "p", 7, 3, 10));
        }

        assertThat(context.promptTokens()).isEqualTo(7);
        assertThat(context.completionTokens()).isEqualTo(3);
        assertThat(context.totalTokens()).isEqualTo(10);
        assertThat(registry.get("zhiwei.agent.node.tokens").tag("node", "classify")
                .tag("type", "prompt").tag("provider", "p").counter().count()).isEqualTo(7);
    }
}

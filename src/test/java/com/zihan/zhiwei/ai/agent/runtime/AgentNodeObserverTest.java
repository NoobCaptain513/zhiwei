package com.zihan.zhiwei.ai.agent.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentNodeObserverTest {

    @Test
    void shouldRecordNodeSuccessFailureDurationAndTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentNodeObserver observer = new AgentNodeObserver(registry);

        assertThat(observer.observe("classify", () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> observer.observe("grade", () -> { throw new IllegalStateException("bad"); }))
                .isInstanceOf(IllegalStateException.class);
        observer.recordTokens("classify", "test-provider", 7, 3);

        assertThat(registry.get("zhiwei.agent.node.calls").tag("node", "classify").counter().count()).isEqualTo(1);
        assertThat(registry.get("zhiwei.agent.node.failures").tag("node", "grade").counter().count()).isEqualTo(1);
        assertThat(registry.get("zhiwei.agent.node.duration").tag("node", "classify").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhiwei.agent.node.tokens").tag("node", "classify").tag("type", "prompt").counter().count()).isEqualTo(7);
        assertThat(registry.get("zhiwei.agent.node.tokens").tag("node", "classify").tag("type", "completion").counter().count()).isEqualTo(3);
    }
}

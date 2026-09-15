package com.zihan.zhiwei.ai.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBudgetTest {

    @Test
    void shouldReserveCommitAndReleaseTokens() {
        TokenBudget budget = new TokenBudget(100, 20, 5, Duration.ofSeconds(5));

        try (TokenBudget.Reservation reservation = budget.reserve("classify", 30, false)) {
            reservation.commit(12, 8);
        }

        assertThat(budget.usedTokens()).isEqualTo(20);
        assertThat(budget.remainingTokens()).isEqualTo(80);
        assertThat(budget.nodeCalls()).isEqualTo(1);
    }

    @Test
    void shouldProtectAnswerReserveFromNonCriticalNodes() {
        TokenBudget budget = new TokenBudget(100, 30, 5, Duration.ofSeconds(5));

        assertThatThrownBy(() -> budget.reserve("grade", 71, false))
                .isInstanceOf(TokenBudgetExceededException.class);
        assertThat(budget.reserve("answer", 100, true)).isNotNull();
    }

    @Test
    void shouldEnforceNodeCallAndDeadlineLimits() throws Exception {
        TokenBudget calls = new TokenBudget(100, 0, 1, Duration.ofSeconds(5));
        calls.reserve("one", 1, false).close();
        assertThatThrownBy(() -> calls.reserve("two", 1, false))
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("NODE_CALL_LIMIT");

        TokenBudget expired = new TokenBudget(100, 0, 2, Duration.ofMillis(1));
        Thread.sleep(10);
        assertThatThrownBy(() -> expired.reserve("late", 1, false))
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    void shouldRejectConcurrentOverReservation() throws Exception {
        TokenBudget budget = new TokenBudget(50, 0, 10, Duration.ofSeconds(5));
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                try (TokenBudget.Reservation ignored = budget.reserve("node", 40, false)) {
                    reserved.countDown();
                    release.await();
                    return 1;
                }
            });
            assertThat(reserved.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(attemptReserve(budget, 40)).isZero();
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void shouldRestoreUsedTokensNodeCallsAndRemainingDeadline() {
        TokenBudget original = new TokenBudget(100, 20, 5, Duration.ofSeconds(5));
        try (TokenBudget.Reservation reservation = original.reserve("classify", 30, false)) {
            reservation.commit(12, 8);
        }

        TokenBudget restored = TokenBudget.restore(original.snapshot());

        assertThat(restored.usedTokens()).isEqualTo(20);
        assertThat(restored.remainingTokens()).isEqualTo(80);
        assertThat(restored.nodeCalls()).isEqualTo(1);
        assertThat(restored.remainingDurationMillis()).isPositive();
        restored.reserve("plan", 10, false).close();
        assertThat(restored.nodeCalls()).isEqualTo(2);
    }

    @Test
    void restoredBudgetCannotExceedCurrentServerPolicy() {
        TokenBudget.Snapshot forged = new TokenBudget.Snapshot(
                1_000_000, 0, 1_000_000, 100, 2, 600_000L);
        TokenBudget serverPolicy = new TokenBudget(500, 100, 5, Duration.ofSeconds(5));

        TokenBudget restored = TokenBudget.restore(forged, serverPolicy);

        assertThat(restored.remainingTokens()).isEqualTo(400);
        assertThat(restored.remainingDurationMillis()).isBetween(1L, 5000L);
        assertThat(restored.snapshot().maxNodeCalls()).isEqualTo(5);
    }

    private static int attemptReserve(TokenBudget budget, int amount) {
        try (TokenBudget.Reservation ignored = budget.reserve("node", amount, false)) {
            return 1;
        } catch (TokenBudgetExceededException e) {
            return 0;
        }
    }
}

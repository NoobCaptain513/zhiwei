package com.zihan.zhiwei.ai.agent.runtime;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe token, node-call and wall-clock budget for one Agent run. */
public final class TokenBudget {

    private final int maxTokens;
    private final int reservedAnswerTokens;
    private final int maxNodeCalls;
    private final long deadlineNanos;
    private int usedTokens;
    private int reservedTokens;
    private int nodeCalls;

    public TokenBudget(int maxTokens, int reservedAnswerTokens, int maxNodeCalls, Duration duration) {
        this(maxTokens, reservedAnswerTokens, maxNodeCalls, duration, 0, 0);
    }

    private TokenBudget(int maxTokens, int reservedAnswerTokens, int maxNodeCalls, Duration duration,
                        int usedTokens, int nodeCalls) {
        this.maxTokens = Math.max(1, maxTokens);
        this.reservedAnswerTokens = Math.min(this.maxTokens, Math.max(0, reservedAnswerTokens));
        this.maxNodeCalls = Math.max(1, maxNodeCalls);
        long durationNanos = Math.max(1L, duration == null ? Duration.ofSeconds(30).toNanos() : duration.toNanos());
        this.deadlineNanos = System.nanoTime() + durationNanos;
        this.usedTokens = Math.min(this.maxTokens, Math.max(0, usedTokens));
        this.nodeCalls = Math.min(this.maxNodeCalls, Math.max(0, nodeCalls));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(maxTokens, reservedAnswerTokens, maxNodeCalls, usedTokens, nodeCalls,
                remainingDurationMillis());
    }

    public static TokenBudget restore(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("token budget snapshot is required");
        return new TokenBudget(snapshot.maxTokens(), snapshot.reservedAnswerTokens(), snapshot.maxNodeCalls(),
                Duration.ofMillis(Math.max(1L, snapshot.remainingDurationMillis())),
                snapshot.usedTokens(), snapshot.nodeCalls());
    }

    /** Restores consumed budget while clamping all limits to the current server policy. */
    public static TokenBudget restore(Snapshot snapshot, TokenBudget serverPolicy) {
        if (snapshot == null) throw new IllegalArgumentException("token budget snapshot is required");
        if (serverPolicy == null) throw new IllegalArgumentException("server budget policy is required");
        Snapshot policy = serverPolicy.snapshot();
        long remainingMillis = Math.max(1L,
                Math.min(Math.max(1L, snapshot.remainingDurationMillis()),
                        Math.max(1L, policy.remainingDurationMillis())));
        int maxTokens = Math.min(Math.max(1, snapshot.maxTokens()), policy.maxTokens());
        int reservedAnswerTokens = Math.min(maxTokens,
                Math.max(Math.max(0, snapshot.reservedAnswerTokens()), policy.reservedAnswerTokens()));
        int maxNodeCalls = Math.min(Math.max(1, snapshot.maxNodeCalls()), policy.maxNodeCalls());
        return new TokenBudget(maxTokens, reservedAnswerTokens, maxNodeCalls,
                Duration.ofMillis(remainingMillis), snapshot.usedTokens(), snapshot.nodeCalls());
    }

    public synchronized Reservation reserve(String node, int estimatedTokens, boolean critical) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TokenBudgetExceededException("DEADLINE_EXCEEDED", "Agent execution deadline reached before " + node);
        }
        if (nodeCalls >= maxNodeCalls) {
            throw new TokenBudgetExceededException("NODE_CALL_LIMIT", "node call limit reached before " + node);
        }
        int requested = Math.max(0, estimatedTokens);
        int usableLimit = critical ? maxTokens : maxTokens - reservedAnswerTokens;
        if ((long) usedTokens + reservedTokens + requested > usableLimit) {
            throw new TokenBudgetExceededException("TOKEN_BUDGET_EXHAUSTED", "insufficient token budget for " + node);
        }
        nodeCalls++;
        reservedTokens += requested;
        return new Reservation(this, requested);
    }

    private synchronized void release(int reserved) {
        reservedTokens = Math.max(0, reservedTokens - reserved);
    }

    private synchronized void commit(int reserved, int promptTokens, int completionTokens) {
        reservedTokens = Math.max(0, reservedTokens - reserved);
        usedTokens += Math.max(0, promptTokens) + Math.max(0, completionTokens);
    }

    public synchronized int usedTokens() {
        return usedTokens;
    }

    public synchronized int remainingTokens() {
        return Math.max(0, maxTokens - usedTokens - reservedTokens);
    }

    public synchronized int nodeCalls() {
        return nodeCalls;
    }

    public long remainingDurationMillis() {
        return Math.max(0L, Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime())).toMillis());
    }

    public record Snapshot(int maxTokens, int reservedAnswerTokens, int maxNodeCalls,
                           int usedTokens, int nodeCalls, long remainingDurationMillis) {}

    public static final class Reservation implements AutoCloseable {
        private final TokenBudget owner;
        private final int reserved;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(TokenBudget owner, int reserved) {
            this.owner = owner;
            this.reserved = reserved;
        }

        public void commit(int promptTokens, int completionTokens) {
            if (closed.compareAndSet(false, true)) {
                owner.commit(reserved, promptTokens, completionTokens);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(reserved);
            }
        }
    }
}

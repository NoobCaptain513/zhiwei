package com.zihan.zhiwei.service;

import java.util.Optional;

/**
 * 幂等请求缓存抽象。业务层只依赖该接口，当前实现由 Redis Lua 状态机提供。
 */
public interface IdempotentRequestCache {

    <T> Optional<T> resolve(String namespace, String userId, String idempotencyKey,
                            Class<T> responseType, String fingerprint);

    IdempotencyLease acquire(String namespace, String userId, String idempotencyKey,
                             String fingerprint, int timeoutSeconds);

    <T> void remember(IdempotencyLease lease, String fingerprint, T response);

    void release(IdempotencyLease lease);

    <T> IdempotencyStatus<T> peek(String namespace, String userId, String idempotencyKey,
                                  Class<T> responseType, String fingerprint);

    String fingerprint(String namespace, Object request);

    enum LeaseState { DISABLED, ACQUIRED, BUSY, COMPLETED }

    record IdempotencyLease(
            LeaseState state,
            String namespace,
            String userId,
            String idempotencyKey,
            String ownerToken
    ) {
        public static IdempotencyLease disabled(String namespace, String userId, String key) {
            return new IdempotencyLease(LeaseState.DISABLED, namespace, userId, key, null);
        }
        public static IdempotencyLease acquired(String namespace, String userId, String key, String token) {
            return new IdempotencyLease(LeaseState.ACQUIRED, namespace, userId, key, token);
        }
        public static IdempotencyLease busy(String namespace, String userId, String key) {
            return new IdempotencyLease(LeaseState.BUSY, namespace, userId, key, null);
        }
        public static IdempotencyLease completed(String namespace, String userId, String key) {
            return new IdempotencyLease(LeaseState.COMPLETED, namespace, userId, key, null);
        }
        public boolean enabled() { return state != LeaseState.DISABLED; }
        public boolean acquired() { return state == LeaseState.ACQUIRED; }
    }

    record IdempotencyStatus<T>(State state, T result) {
        public enum State { COMPLETED, PROCESSING, NOT_FOUND }
        public static <T> IdempotencyStatus<T> completed(T result) {
            return new IdempotencyStatus<>(State.COMPLETED, result);
        }
        public static <T> IdempotencyStatus<T> processing() {
            return new IdempotencyStatus<>(State.PROCESSING, null);
        }
        public static <T> IdempotencyStatus<T> notFound() {
            return new IdempotencyStatus<>(State.NOT_FOUND, null);
        }
        public boolean isCompleted() { return state == State.COMPLETED; }
        public boolean isProcessing() { return state == State.PROCESSING; }
    }
}

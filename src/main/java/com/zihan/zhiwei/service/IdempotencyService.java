package com.zihan.zhiwei.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.common.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.zihan.zhiwei.service.IdempotentRequestCache.IdempotencyLease;
import com.zihan.zhiwei.service.IdempotentRequestCache.IdempotencyStatus;

/**
 * 基于 Redis Lua 原子状态机的幂等请求缓存。
 *
 * <p>提供结果缓存、带所有者 token 的处理锁、并发等待、请求指纹和业务命名空间隔离。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService implements IdempotentRequestCache {

    private static final String KEY_PREFIX = "zhiwei:idempotency:";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final int WAIT_TIMEOUT_MS = 30_000;

    private static final long ACQUIRE_BUSY = 0L;
    private static final long ACQUIRE_SUCCESS = 1L;
    private static final long ACQUIRE_COMPLETED = 2L;
    private static final long ACQUIRE_CONFLICT = -1L;

    /**
     * KEYS: result, lock, fingerprint
     * ARGV: ownerToken, requestFingerprint, lockTtlMs
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local result = redis.call('GET', KEYS[1])
            local storedFingerprint = redis.call('GET', KEYS[3])
            if ARGV[2] ~= '' and storedFingerprint and storedFingerprint ~= ARGV[2] then
                return -1
            end
            if result then
                return 2
            end
            local acquired = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[3])
            if acquired then
                if ARGV[2] ~= '' then
                    redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[3])
                end
                return 1
            end
            return 0
            """, Long.class);

    /**
     * 仅锁所有者可以把 PROCESSING 原子切换为 COMPLETED。
     * KEYS: result, lock, fingerprint
     * ARGV: ownerToken, responseJson, requestFingerprint, resultTtlMs
     */
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[4])
            if ARGV[3] ~= '' then
                redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
            end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    /**
     * 仅锁所有者可以释放锁；失败且没有结果时同时删除临时指纹。
     * KEYS: result, lock, fingerprint
     * ARGV: ownerToken
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[2])
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[3])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<IdempotencyLease> legacyLease = new ThreadLocal<>();

    @Value("${zhiwei.ai.idempotency.ttl-hours:24}")
    private long ttlHours;

    /** 兼容旧调用。新业务代码应使用带 namespace/fingerprint 的接口。 */
    public <T> Optional<T> resolve(String userId, String idempotencyKey, Class<T> clazz) {
        return resolve(DEFAULT_NAMESPACE, userId, idempotencyKey, clazz, null);
    }

    @Deprecated
    public boolean tryAcquire(String userId, String idempotencyKey, int timeoutSeconds) {
        IdempotencyLease lease = acquire(
                DEFAULT_NAMESPACE, userId, idempotencyKey, null, timeoutSeconds);
        legacyLease.set(lease);
        return lease.acquired() || !lease.enabled();
    }

    @Deprecated
    public <T> void remember(String userId, String idempotencyKey, T response) {
        IdempotencyLease lease = legacyLease.get();
        legacyLease.remove();
        if (lease == null) {
            lease = acquire(DEFAULT_NAMESPACE, userId, idempotencyKey, null, 300);
        }
        if (lease.acquired()) {
            remember(lease, null, response);
        }
    }

    @Deprecated
    public void release(String userId, String idempotencyKey) {
        IdempotencyLease lease = legacyLease.get();
        legacyLease.remove();
        if (lease != null) {
            release(lease);
        }
    }

    public <T> IdempotencyStatus<T> peek(
            String userId, String idempotencyKey, Class<T> clazz) {
        return peek(DEFAULT_NAMESPACE, userId, idempotencyKey, clazz, null);
    }

    /** 查询完成结果；存在处理中锁时等待首个请求提交。 */
    @Override
    public <T> Optional<T> resolve(String namespace, String userId, String idempotencyKey,
                                    Class<T> clazz, String fingerprint) {
        if (!hasKey(idempotencyKey)) {
            return Optional.empty();
        }
        RedisKeys keys = keys(namespace, userId, idempotencyKey);
        Optional<T> completed = readCompleted(
                keys, namespace, idempotencyKey, fingerprint, clazz);
        if (completed.isPresent()) {
            return completed;
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(keys.lock()))) {
            validateFingerprint(keys, namespace, idempotencyKey, fingerprint);
            return waitForProcessing(
                    keys, namespace, idempotencyKey, fingerprint, clazz, WAIT_TIMEOUT_MS);
        }
        return Optional.empty();
    }

    /**
     * 原子获取处理权。返回的 lease 必须原样传给 remember/release。
     */
    @Override
    public IdempotencyLease acquire(String namespace, String userId, String idempotencyKey,
                                    String fingerprint, int timeoutSeconds) {
        if (!hasKey(idempotencyKey)) {
            return IdempotencyLease.disabled(namespace, userId, idempotencyKey);
        }
        RedisKeys keys = keys(namespace, userId, idempotencyKey);
        String ownerToken = UUID.randomUUID().toString();
        long lockTtlMs = TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds));
        Long result = stringRedisTemplate.execute(
                ACQUIRE_SCRIPT,
                keys.asList(),
                ownerToken,
                fingerprint == null ? "" : fingerprint,
                String.valueOf(lockTtlMs));

        long state = result == null ? ACQUIRE_BUSY : result;
        if (state == ACQUIRE_CONFLICT) {
            throw conflict(namespace, idempotencyKey);
        }
        if (state == ACQUIRE_SUCCESS) {
            log.info("[Idempotency] acquired lock namespace={} key={} owner={}",
                    namespace, keys.base(), ownerToken);
            return IdempotencyLease.acquired(namespace, userId, idempotencyKey, ownerToken);
        }
        if (state == ACQUIRE_COMPLETED) {
            return IdempotencyLease.completed(namespace, userId, idempotencyKey);
        }
        log.info("[Idempotency] lock busy namespace={} key={}", namespace, keys.base());
        return IdempotencyLease.busy(namespace, userId, idempotencyKey);
    }

    /**
     * 写入完成结果。事务存在时延迟到数据库提交成功后再写 Redis，避免缓存脏结果。
     */
    @Override
    public <T> void remember(IdempotencyLease lease, String fingerprint, T response) {
        if (!lease.enabled()) {
            return;
        }
        if (!lease.acquired()) {
            throw new IllegalStateException("只有锁所有者可以提交幂等结果");
        }

        final String responseJson;
        try {
            responseJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            release(lease);
            log.warn("[Idempotency] serialize failed namespace={} key={}: {}",
                    lease.namespace(), lease.idempotencyKey(), e.getMessage());
            return;
        }

        Runnable complete = () -> completeNow(lease, fingerprint, responseJson, response.getClass());
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    complete.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        release(lease);
                    }
                }
            });
        } else {
            complete.run();
        }
    }

    /** 仅锁所有者可以释放处理锁。 */
    @Override
    public void release(IdempotencyLease lease) {
        if (!lease.enabled() || !lease.acquired()) {
            return;
        }
        RedisKeys keys = keys(lease.namespace(), lease.userId(), lease.idempotencyKey());
        Long released = stringRedisTemplate.execute(
                RELEASE_SCRIPT, keys.asList(), lease.ownerToken());
        if (Long.valueOf(1L).equals(released)) {
            log.info("[Idempotency] released lock namespace={} key={} owner={}",
                    lease.namespace(), keys.base(), lease.ownerToken());
        } else {
            log.debug("[Idempotency] skip release: owner changed namespace={} key={} owner={}",
                    lease.namespace(), keys.base(), lease.ownerToken());
        }
    }

    /** 只查询一次，不等待。 */
    @Override
    public <T> IdempotencyStatus<T> peek(String namespace, String userId, String idempotencyKey,
                                         Class<T> clazz, String fingerprint) {
        if (!hasKey(idempotencyKey)) {
            return IdempotencyStatus.notFound();
        }
        RedisKeys keys = keys(namespace, userId, idempotencyKey);
        Optional<T> completed = readCompleted(
                keys, namespace, idempotencyKey, fingerprint, clazz);
        if (completed.isPresent()) {
            return IdempotencyStatus.completed(completed.get());
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(keys.lock()))) {
            validateFingerprint(keys, namespace, idempotencyKey, fingerprint);
            return IdempotencyStatus.processing();
        }
        return IdempotencyStatus.notFound();
    }

    /** 生成稳定的请求指纹。 */
    @Override
    public String fingerprint(String namespace, Object request) {
        try {
            String payload = namespace + ":" + objectMapper.writeValueAsString(request);
            return sha256(payload);
        } catch (Exception e) {
            throw new BusinessException("无法生成幂等请求指纹: " + e.getMessage());
        }
    }

    private <T> Optional<T> readCompleted(RedisKeys keys, String namespace,
                                           String idempotencyKey, String fingerprint,
                                           Class<T> clazz) {
        String json = stringRedisTemplate.opsForValue().get(keys.result());
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        validateFingerprint(keys, namespace, idempotencyKey, fingerprint);
        try {
            T value = objectMapper.readValue(json, clazz);
            log.info("[Idempotency] cache hit namespace={} key={} type={}",
                    namespace, keys.base(), clazz.getSimpleName());
            return Optional.of(value);
        } catch (Exception e) {
            log.warn("[Idempotency] deserialize failed key={}: {}", keys.base(), e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> waitForProcessing(RedisKeys keys, String namespace,
                                               String idempotencyKey, String fingerprint,
                                               Class<T> clazz, int maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<T> completed = readCompleted(
                    keys, namespace, idempotencyKey, fingerprint, clazz);
            if (completed.isPresent()) {
                return completed;
            }
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(keys.lock()))) {
                return Optional.empty();
            }
        }
        log.warn("[Idempotency] wait timeout namespace={} key={}", namespace, keys.base());
        return Optional.empty();
    }

    private void completeNow(IdempotencyLease lease, String fingerprint,
                             String responseJson, Class<?> responseType) {
        RedisKeys keys = keys(lease.namespace(), lease.userId(), lease.idempotencyKey());
        long resultTtlMs = TimeUnit.HOURS.toMillis(Math.max(1, ttlHours));
        Long completed = stringRedisTemplate.execute(
                COMPLETE_SCRIPT,
                keys.asList(),
                lease.ownerToken(),
                responseJson,
                fingerprint == null ? "" : fingerprint,
                String.valueOf(resultTtlMs));
        if (Long.valueOf(1L).equals(completed)) {
            log.info("[Idempotency] completed namespace={} key={} type={} ttl={}h owner={}",
                    lease.namespace(), keys.base(), responseType.getSimpleName(),
                    ttlHours, lease.ownerToken());
        } else {
            log.warn("[Idempotency] stale owner cannot complete namespace={} key={} owner={}",
                    lease.namespace(), keys.base(), lease.ownerToken());
        }
    }

    private void validateFingerprint(RedisKeys keys, String namespace,
                                     String idempotencyKey, String fingerprint) {
        if (fingerprint == null) {
            return;
        }
        String stored = stringRedisTemplate.opsForValue().get(keys.fingerprint());
        if (stored != null && !stored.equals(fingerprint)) {
            throw conflict(namespace, idempotencyKey);
        }
    }

    private IdempotencyConflictException conflict(String namespace, String idempotencyKey) {
        return new IdempotencyConflictException(
                "Idempotency-Key 已被其他请求使用：namespace=" + namespace
                        + ", key=" + idempotencyKey + ", 请求内容不一致");
    }

    private RedisKeys keys(String namespace, String userId, String idempotencyKey) {
        String normalizedNamespace = safe(namespace);
        String identity = sha256(normalizedNamespace + "\u0000" + safe(userId)
                + "\u0000" + idempotencyKey);
        // 使用相同 Redis Cluster hash tag，确保 Lua 的三个 KEYS 位于同一 slot。
        String base = KEY_PREFIX + normalizedNamespace + ":{" + identity + "}";
        return new RedisKeys(base, base + ":result", base + ":lock", base + ":fingerprint");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static boolean hasKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    private record RedisKeys(String base, String result, String lock, String fingerprint) {
        List<String> asList() {
            return List.of(result, lock, fingerprint);
        }
    }

}

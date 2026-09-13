package com.zihan.zhiwei.ai.memory.impl;

import com.zihan.zhiwei.ai.memory.MemoryCache;
import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisMemoryCache implements MemoryCache {
    private static final String PREFIX = "zhiwei:memory:";
    private final StringRedisTemplate redis;

    @Override
    public Optional<String> get(Key key) {
        return Optional.ofNullable(redis.opsForValue().get(redisKey(key)));
    }

    @Override
    public void put(Key key, String payload, Duration ttl) {
        if (payload == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("payload and positive ttl are required");
        }
        String redisKey = redisKey(key);
        redis.opsForValue().set(redisKey, payload, ttl);
        redis.opsForSet().add(ownerIndexKey(key.userId()), redisKey);
        if (key.conversationId() != null) {
            redis.opsForSet().add(conversationIndexKey(key.userId(), key.conversationId()), redisKey);
        }
    }

    @Override
    public void evict(MemoryForgetJob.ScopeType scopeType, String userId, String scopeId) {
        if (scopeType == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("scopeType and userId are required");
        }
        switch (scopeType) {
            case FACT -> deleteKeys(Set.of(redisKey(Key.fact(userId, requiredScope(scopeId)))));
            case SUMMARY -> deleteKeys(Set.of(redisKey(Key.summary(userId, requiredScope(scopeId)))));
            case CHECKPOINT -> evictCheckpoint(userId, requiredScope(scopeId));
            case CONVERSATION -> evictIndex(conversationIndexKey(userId, requiredScope(scopeId)));
            case USER -> evictIndex(ownerIndexKey(userId));
        }
    }

    public String redisKey(Key key) {
        String root = PREFIX + ownerHash(key.userId()) + ":" + key.region().name().toLowerCase();
        return key.region() == Region.CHECKPOINT
                ? root + ":" + key.conversationId() + ":" + key.resourceId()
                : root + ":" + key.resourceId();
    }

    public String ownerIndexKey(String userId) {
        return PREFIX + ownerHash(userId) + ":index";
    }

    public String conversationIndexKey(String userId, String conversationId) {
        return PREFIX + ownerHash(userId) + ":conversation:" + conversationId + ":index";
    }

    private void evictCheckpoint(String userId, String checkpointId) {
        Set<String> members = safeMembers(ownerIndexKey(userId));
        String suffix = ":" + checkpointId;
        deleteKeys(members.stream().filter(k -> k.contains(":checkpoint:") && k.endsWith(suffix)).collect(java.util.stream.Collectors.toSet()));
    }

    private void evictIndex(String index) {
        Set<String> keys = safeMembers(index);
        deleteKeys(keys);
        redis.delete(index);
    }

    private Set<String> safeMembers(String index) {
        Set<String> members = redis.opsForSet().members(index);
        return members == null ? Set.of() : new HashSet<>(members);
    }

    private void deleteKeys(Set<String> keys) {
        if (!keys.isEmpty()) redis.delete(keys);
    }

    private String ownerHash(String owner) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(owner.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash cache owner", e);
        }
    }

    private String requiredScope(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) throw new IllegalArgumentException("scopeId is required");
        return scopeId;
    }
}

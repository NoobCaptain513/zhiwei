package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;

import java.time.Duration;
import java.util.Optional;

/** Redis-backed memory acceleration. MySQL remains authoritative. */
public interface MemoryCache {
    Optional<String> get(Key key);

    void put(Key key, String payload, Duration ttl);

    void evict(MemoryForgetJob.ScopeType scopeType, String userId, String scopeId);

    enum Region { FACT, SUMMARY, CHECKPOINT }

    record Key(Region region, String userId, String conversationId, String resourceId) {
        public Key {
            if (region == null || blank(userId) || blank(resourceId)) {
                throw new IllegalArgumentException("region, userId and resourceId are required");
            }
            if (region == Region.CHECKPOINT && blank(conversationId)) {
                throw new IllegalArgumentException("checkpoint conversationId is required");
            }
        }

        public static Key fact(String userId, String factId) {
            return new Key(Region.FACT, userId, null, factId);
        }

        public static Key summary(String userId, String conversationId) {
            return new Key(Region.SUMMARY, userId, conversationId, conversationId);
        }

        public static Key checkpoint(String userId, String conversationId, String checkpointId) {
            return new Key(Region.CHECKPOINT, userId, conversationId, checkpointId);
        }

        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}

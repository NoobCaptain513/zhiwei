package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.impl.RedisMemoryCache;
import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMemoryCacheTest {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock SetOperations<String, String> sets;
    private RedisMemoryCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        cache = new RedisMemoryCache(redis);
    }

    @Test
    void storesOwnerOpaqueKeysAndReadsPayload() {
        var key = MemoryCache.Key.fact("owner-a", "42");
        when(values.get(anyString())).thenReturn("payload");

        cache.put(key, "payload", Duration.ofMinutes(5));

        String redisKey = captureWrittenKey();
        assertThat(redisKey).doesNotContain("owner-a").endsWith(":fact:42");
        verify(values).set(redisKey, "payload", Duration.ofMinutes(5));
        assertThat(cache.get(key)).contains("payload");
        verify(values).get(redisKey);
    }

    @Test
    void conversationEvictionRemovesSummaryAndEveryCheckpointInConversation() {
        var summary = MemoryCache.Key.summary("owner-a", "77");
        var checkpoint = MemoryCache.Key.checkpoint("owner-a", "77", "91");
        cache.put(summary, "summary", Duration.ofMinutes(5));
        cache.put(checkpoint, "state", Duration.ofMinutes(5));
        String conversationIndex = cache.conversationIndexKey("owner-a", "77");
        when(sets.members(conversationIndex)).thenReturn(Set.of(cache.redisKey(summary), cache.redisKey(checkpoint)));

        cache.evict(MemoryForgetJob.ScopeType.CONVERSATION, "owner-a", "77");

        verify(redis).delete(Set.of(cache.redisKey(summary), cache.redisKey(checkpoint)));
        verify(redis).delete(conversationIndex);
    }

    @Test
    void userEvictionOnlyUsesThatOwnersIndex() {
        var fact = MemoryCache.Key.fact("owner-a", "42");
        cache.put(fact, "secret", Duration.ofMinutes(5));
        String ownerIndex = cache.ownerIndexKey("owner-a");
        when(sets.members(ownerIndex)).thenReturn(Set.of(cache.redisKey(fact)));

        cache.evict(MemoryForgetJob.ScopeType.USER, "owner-a", null);

        verify(redis).delete(Set.of(cache.redisKey(fact)));
        verify(redis).delete(ownerIndex);
        verify(redis, never()).keys(anyString());
    }

    private String captureWrittenKey() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(captor.capture(), eq("payload"), eq(Duration.ofMinutes(5)));
        return captor.getValue();
    }
}

package com.zihan.zhiwei.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Redis 状态机")
class IdempotencyServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(redis, new ObjectMapper());
        ReflectionTestUtils.setField(service, "ttlHours", 24L);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void completedResultCanBeResolved() {
        when(valueOps.get(anyString())).thenReturn(
                "{\"conversationId\":1,\"messageId\":11,\"content\":\"cached\","
                        + "\"model\":\"m\",\"provider\":\"p\",\"totalTokens\":1}");

        Optional<ChatResponse> result = service.resolve("chat", "u1", "key", ChatResponse.class, null);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("cached");
    }

    @Test
    void identityIsHashedAndIsolatedByUser() {
        when(valueOps.get(anyString())).thenReturn(null);
        service.resolve("chat", "u1", "same-key", ChatResponse.class, null);
        service.resolve("chat", "u2", "same-key", ChatResponse.class, null);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).get(keys.capture());
        assertThat(keys.getAllValues()).allMatch(k ->
                k.startsWith("zhiwei:idempotency:chat:{") && k.endsWith("}:result"));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void blankKeyDisablesIdempotency() {
        assertThat(service.resolve("chat", "u1", " ", ChatResponse.class, "fp")).isEmpty();
        assertThat(service.acquire("chat", "u1", null, "fp", 300).enabled()).isFalse();
        verifyNoInteractions(valueOps);
    }

    @Test
    void corruptedJsonIsTreatedAsMiss() {
        when(valueOps.get(anyString())).thenReturn("not-json");
        assertThat(service.resolve("chat", "u1", "key", ChatResponse.class, null)).isEmpty();
    }

    @Test
    void acquireUsesAtomicRedisScript() {
        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(1L);

        IdempotentRequestCache.IdempotencyLease lease =
                service.acquire("chat", "u1", "key", "fp", 300);

        assertThat(lease.acquired()).isTrue();
        assertThat(lease.ownerToken()).isNotBlank();
    }

    @Test
    void nullScriptResultIsBusyNotAcquired() {
        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(null);
        assertThat(service.acquire("chat", "u1", "key", "fp", 300).acquired()).isFalse();
    }

    @Test
    void rememberCompletesOnlyAfterTransactionCommit() {
        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(1L);
        var lease = IdempotentRequestCache.IdempotencyLease.acquired("chat", "u1", "key", "owner");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.remember(lease, "fp", sampleResponse());
        verify(redis, never()).execute(anyRedisScript(), anyList(), any(Object[].class));

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(redis).execute(anyRedisScript(), anyList(), any(Object[].class));
    }

    @Test
    void releaseUsesOwnerCheckedRedisScript() {
        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(1L);
        var lease = IdempotentRequestCache.IdempotencyLease.acquired("chat", "u1", "key", "owner");

        service.release(lease);

        verify(redis).execute(anyRedisScript(), anyList(), any(Object[].class));
    }

    @Test
    void fingerprintIsStableAndNamespaceSensitive() {
        ChatResponse response = sampleResponse();
        assertThat(service.fingerprint("chat", response))
                .isEqualTo(service.fingerprint("chat", response))
                .isNotEqualTo(service.fingerprint("agent", response));
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyRedisScript() {
        return (RedisScript<Long>) any(RedisScript.class);
    }

    private static ChatResponse sampleResponse() {
        return new ChatResponse(1L, 11L, "cached", "m", "p", 1);
    }
}
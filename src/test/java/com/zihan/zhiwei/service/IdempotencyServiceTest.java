package com.zihan.zhiwei.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.reply.AgentReply;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 幂等键服务测试。
 * 验证：同一 userId + idempotencyKey 命中缓存返回首次结果；
 * 不同 userId 或不同 key 互不干扰；无 key 时不做幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IdempotencyService 幂等键服务测试")
class IdempotencyServiceTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(stringRedisTemplate, new ObjectMapper());
        // 模拟 @Value("${zhiwei.ai.idempotency.ttl-hours:24}") 注入
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ttlHours", 24L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private static ChatResponse sampleChatResponse() {
        return new ChatResponse(1L, 11L, "Kubernetes 部署方式有...", "qwen-plus", "spring-ai-alibaba", 120);
    }

    @Nested
    @DisplayName("resolve 命中缓存")
    class ResolveHit {

        @Test
        @DisplayName("同一 userId + 同一 key 返回首次结果")
        void sameUserSameKeyReturnsCached() {
            when(valueOps.get("zhiwei:idempotency:user1:key-abc"))
                    .thenReturn("{\"conversationId\":1,\"messageId\":11,\"content\":\"Kubernetes 部署方式有...\",\"model\":\"qwen-plus\",\"provider\":\"spring-ai-alibaba\",\"totalTokens\":120}");

            Optional<ChatResponse> result = service.resolve("user1", "key-abc", ChatResponse.class);

            assertThat(result).isPresent();
            assertThat(result.get().content()).isEqualTo("Kubernetes 部署方式有...");
            assertThat(result.get().totalTokens()).isEqualTo(120);
        }

        @Test
        @DisplayName("不同 userId 用同一个 key 互不干扰")
        void differentUserSameKeyIsolated() {
            when(valueOps.get("zhiwei:idempotency:user1:key-abc"))
                    .thenReturn("{\"conversationId\":1,\"messageId\":11,\"content\":\"cached\",\"model\":\"m\",\"provider\":\"p\",\"totalTokens\":1}");
            when(valueOps.get("zhiwei:idempotency:user2:key-abc"))
                    .thenReturn(null);

            assertThat(service.resolve("user1", "key-abc", ChatResponse.class)).isPresent();
            assertThat(service.resolve("user2", "key-abc", ChatResponse.class)).isEmpty();
        }

        @Test
        @DisplayName("缓存 JSON 损坏 → 按未命中处理，不抛异常")
        void corruptedJsonFallsBackToMiss() {
            when(valueOps.get("zhiwei:idempotency:user1:key-abc"))
                    .thenReturn("not-a-json{{");

            Optional<ChatResponse> result = service.resolve("user1", "key-abc", ChatResponse.class);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolve 未命中/无 key")
    class ResolveMiss {

        @Test
        @DisplayName("key 为空 → 不做幂等")
        void blankKeySkipsIdempotency() {
            assertThat(service.resolve("user1", null, ChatResponse.class)).isEmpty();
            assertThat(service.resolve("user1", "  ", ChatResponse.class)).isEmpty();
            verify(valueOps, never()).get(anyString());
        }

        @Test
        @DisplayName("Redis 无值 → 未命中")
        void noRedisValueIsMiss() {
            when(valueOps.get("zhiwei:idempotency:user1:key-abc")).thenReturn(null);
            assertThat(service.resolve("user1", "key-abc", ChatResponse.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("remember 写入缓存")
    class Remember {

        @Test
        @DisplayName("成功写入 Redis，key 带 userId 前缀，TTL 24 小时")
        void storesWithKeyPrefixAndTtl() {
            ChatResponse response = sampleChatResponse();

            service.remember("user1", "key-abc", response);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("zhiwei:idempotency:user1:key-abc"),
                    jsonCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
            assertThat(jsonCaptor.getValue()).contains("\"totalTokens\":120");
            assertThat(jsonCaptor.getValue()).contains("\"spring-ai-alibaba\"");
        }

        @Test
        @DisplayName("key 为空 → 不写缓存")
        void blankKeyDoesNotWrite() {
            service.remember("user1", null, sampleChatResponse());
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("AgentResponse（含卡片）可序列化")
        void agentResponseWithCardsSerializable() {
            AgentResponse response = AgentResponse.builder()
                    .conversationId(1L)
                    .messageId(2L)
                    .content("服务器状态如下")
                    .cards(List.of(AgentReply.Card.builder()
                            .type("server")
                            .title("web-server-01")
                            .fields(Map.of("status", "RUNNING", "cpu", "45%"))
                            .build()))
                    .intent("fault")
                    .provider("native-dashscope")
                    .model("qwen-turbo")
                    .totalTokens(88)
                    .build();

            service.remember("user1", "key-xyz", response);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("zhiwei:idempotency:user1:key-xyz"),
                    jsonCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
            assertThat(jsonCaptor.getValue()).contains("\"cards\"");
            assertThat(jsonCaptor.getValue()).contains("\"web-server-01\"");
        }

        @Test
        @DisplayName("remember 后 resolve 能还原（序列化/反序列化闭环）")
        void roundTripChatResponse() {
            ChatResponse response = sampleChatResponse();

            service.remember("user1", "key-roundtrip", response);
            // 模拟 Redis 把刚才写入的值读出来
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("zhiwei:idempotency:user1:key-roundtrip"),
                    jsonCaptor.capture(), anyLong(), any(TimeUnit.class));
            when(valueOps.get("zhiwei:idempotency:user1:key-roundtrip")).thenReturn(jsonCaptor.getValue());

            Optional<ChatResponse> cached = service.resolve("user1", "key-roundtrip", ChatResponse.class);

            assertThat(cached).isPresent();
            assertThat(cached.get()).isEqualTo(response);
        }
    }
    @Nested
    @DisplayName("并发保护（第一步新增）")
    class ConcurrencyProtection {

        @Test
        @DisplayName("tryAcquire 成功获取锁")
        void tryAcquireSucceeds() {
            when(valueOps.setIfAbsent(eq("zhiwei:idempotency:user1:key-abc:lock"),
                    eq("PROCESSING"), eq(300L), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);

            boolean acquired = service.tryAcquire("user1", "key-abc", 300);

            assertThat(acquired).isTrue();
        }

        @Test
        @DisplayName("tryAcquire 失败（已被占位）")
        void tryAcquireFails() {
            when(valueOps.setIfAbsent(eq("zhiwei:idempotency:user1:key-abc:lock"),
                    eq("PROCESSING"), eq(300L), eq(TimeUnit.SECONDS)))
                    .thenReturn(false);

            boolean acquired = service.tryAcquire("user1", "key-abc", 300);

            assertThat(acquired).isFalse();
        }

        @Test
        @DisplayName("release 释放锁")
        void releaseDeletesLock() {
            service.release("user1", "key-abc");

            verify(stringRedisTemplate).delete("zhiwei:idempotency:user1:key-abc:lock");
        }

        @Test
        @DisplayName("remember 后自动释放锁")
        void rememberReleasesLock() {
            ChatResponse response = sampleChatResponse();

            service.remember("user1", "key-abc", response);

            verify(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            verify(stringRedisTemplate).delete("zhiwei:idempotency:user1:key-abc:lock");
        }

        @Test
        @DisplayName("key 为空时 tryAcquire 直接返回 true")
        void tryAcquireSkipsWhenNoKey() {
            assertThat(service.tryAcquire("user1", null, 300)).isTrue();
            assertThat(service.tryAcquire("user1", "  ", 300)).isTrue();
            verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("key 为空时 release 不操作")
        void releaseSkipsWhenNoKey() {
            service.release("user1", null);
            service.release("user1", "  ");
            verify(stringRedisTemplate, never()).delete(anyString());
        }
    }
}

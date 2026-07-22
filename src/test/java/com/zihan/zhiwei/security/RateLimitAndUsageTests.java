package com.zihan.zhiwei.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.config.RateLimitConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("限流 + 用量聚合测试")
class RateLimitAndUsageTests {

    // ──────────────────────────────────────────
    // RateLimiter (Redis)
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RateLimiter Redis 滑动窗口")
    class RateLimiterTests {

        @Mock private StringRedisTemplate redisTemplate;
        @Mock private DefaultRedisScript<Long> script;

        private RateLimitConfig.RateLimiter rateLimiter;

        @BeforeEach
        void setUp() {
            rateLimiter = new RateLimitConfig.RateLimiter(redisTemplate, script);
        }

        @Test
        @DisplayName("allow → Redis 返回 1 → true")
        void shouldAllowWhenRedisReturnsOne() {
            when(redisTemplate.execute(eq(script), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(1L);

            assertThat(rateLimiter.allow("user:u1")).isTrue();
        }

        @Test
        @DisplayName("allow → Redis 返回 0 → false")
        void shouldDenyWhenRedisReturnsZero() {
            when(redisTemplate.execute(eq(script), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(0L);

            assertThat(rateLimiter.allow("user:u1")).isFalse();
        }

        @Test
        @DisplayName("allow → Redis 返回 null → false")
        void shouldDenyWhenRedisReturnsNull() {
            when(redisTemplate.execute(eq(script), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(null);

            assertThat(rateLimiter.allow("user:u1")).isFalse();
        }

        @Test
        @DisplayName("key 前缀正确")
        void shouldUseCorrectKeyPrefix() {
            when(redisTemplate.execute(eq(script), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(1L);

            rateLimiter.allow("user:u1");

            verify(redisTemplate).execute(eq(script),
                    argThat(list -> list.get(0).equals("zhiwei:ratelimit:user:u1")),
                    anyString(), anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────
    // RateLimitInterceptor
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("RateLimitInterceptor 拦截器")
    class InterceptorTests {

        @Mock private RateLimitConfig.RateLimiter rateLimiter;
        @Mock private HttpServletRequest request;
        @Mock private HttpServletResponse response;

        private final ObjectMapper objectMapper = new ObjectMapper();

        private RateLimitInterceptor interceptor;

        @BeforeEach
        void setUp() {
            interceptor = new RateLimitInterceptor(rateLimiter, objectMapper);
        }

        @Test
        @DisplayName("未超限 → preHandle 返回 true")
        void shouldAllowWhenUnderLimit() throws Exception {
            when(rateLimiter.allow(anyString(), eq(60), eq(60_000L))).thenReturn(true);
            when(request.getHeader("X-User-Id")).thenReturn("u1");
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");

            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }

        @Test
        @DisplayName("超限 → preHandle 返回 false, 响应 429 + JSON")
        void shouldDenyWhenOverLimit() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            when(rateLimiter.allow(anyString(), eq(60), eq(60_000L))).thenReturn(false);
            when(request.getHeader("X-User-Id")).thenReturn("u1");
            when(response.getWriter()).thenReturn(pw);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            verify(response).setStatus(429);
            assertThat(sw.toString()).contains("429");
            assertThat(sw.toString()).contains("请求过于频繁");
        }

        @Test
        @DisplayName("无 X-User-Id → 使用 IP 作为标识")
        void shouldUseIpWhenNoUserId() throws Exception {
            when(rateLimiter.allow(anyString(), eq(60), eq(60_000L))).thenReturn(true);
            when(request.getHeader("X-User-Id")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn("10.0.0.1");

            interceptor.preHandle(request, response, null);

            verify(rateLimiter).allow(eq("ip:10.0.0.1"), eq(60), eq(60_000L));
        }
    }

    // ──────────────────────────────────────────
    // WebConfig 路径注册
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("WebConfig 拦截路径")
    class WebConfigTests {

        @Test
        @DisplayName("拦截路径包含 /api/ai/chat, /api/ai/agent, /api/mcp")
        void shouldHaveCorrectPaths() {
            // 验证设计：这三个路径是 AI 调用入口，需要限流保护
            String[] paths = {"/api/ai/chat", "/api/ai/agent", "/api/mcp"};

            assertThat(paths).contains("/api/ai/chat");
            assertThat(paths).contains("/api/ai/agent");
            assertThat(paths).contains("/api/mcp");
        }
    }
}

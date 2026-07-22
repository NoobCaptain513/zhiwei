package com.zihan.zhiwei.ai.usage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
import com.zihan.zhiwei.mapper.AiUsageLogMapper;
import com.zihan.zhiwei.pojo.dto.UsageRecentItem;
import com.zihan.zhiwei.pojo.entity.AiUsageLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UsageRecorder 用量记录器测试")
class UsageRecorderTest {

    @Mock private AiUsageLogMapper aiUsageLogMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private CostCalibrationInterceptor costCalibrationInterceptor;

    private final ObjectMapper realObjectMapper = new ObjectMapper();
    private UsageRecorder recorder;

    private static final String PROVIDER = "spring-ai-alibaba";
    private static final String MODEL = "qwen-plus";

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        recorder = new UsageRecorder(
                aiUsageLogMapper, stringRedisTemplate, costCalibrationInterceptor, realObjectMapper);
    }

    // ──────────────────────────────────────────
    // 第一部分：成功 / 降级记录
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("记录成功调用 record()")
    class RecordSuccessTests {

        @Test
        @DisplayName("正常调用 → status=SUCCESS, 写 MySQL + Redis")
        void shouldRecordSuccess() {
            when(costCalibrationInterceptor.estimateCost(100, 50))
                    .thenReturn(new BigDecimal("0.000800"));

            ProviderChatResponse response = new ProviderChatResponse(
                    "AI回复", MODEL, PROVIDER, 100, 50, 150);

            recorder.record(1L, 10L, response, UsageRecorder.MODE_CHAT, 320L, false);

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            AiUsageLog log = captor.getValue();

            assertThat(log.getConversationId()).isEqualTo(1L);
            assertThat(log.getMessageId()).isEqualTo(10L);
            assertThat(log.getProvider()).isEqualTo(PROVIDER);
            assertThat(log.getModel()).isEqualTo(MODEL);
            assertThat(log.getMode()).isEqualTo("chat");
            assertThat(log.getPromptTokens()).isEqualTo(100);
            assertThat(log.getCompletionTokens()).isEqualTo(50);
            assertThat(log.getTotalTokens()).isEqualTo(150);
            assertThat(log.getCost()).isEqualByComparingTo("0.000800");
            assertThat(log.getLatencyMs()).isEqualTo(320L);
            assertThat(log.getStatus()).isEqualTo(UsageRecorder.STATUS_SUCCESS);
            assertThat(log.getCreateTime()).isNotNull();

            verify(listOps).leftPush(startsWith("zhiwei:provider:metrics:window:"), anyString());
            verify(listOps).trim(anyString(), eq(0L), anyLong());
            verify(stringRedisTemplate).expire(startsWith("zhiwei:provider:metrics:window:"), eq(24L), eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("降级调用 → status=DEGRADED")
        void shouldRecordDegraded() {
            when(costCalibrationInterceptor.estimateCost(anyInt(), anyInt()))
                    .thenReturn(BigDecimal.valueOf(0.001));

            ProviderChatResponse response = new ProviderChatResponse(
                    "降级回复", MODEL, "langchain4j-openai", 80, 40, 120);

            recorder.record(2L, 20L, response, UsageRecorder.MODE_CHAT, 450L, true);

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());

            assertThat(captor.getValue().getStatus()).isEqualTo(UsageRecorder.STATUS_DEGRADED);
            assertThat(captor.getValue().getProvider()).isEqualTo("langchain4j-openai");
        }

        @Test
        @DisplayName("mode 为空/空白 → 默认 chat")
        void shouldDefaultModeToChat() {
            when(costCalibrationInterceptor.estimateCost(anyInt(), anyInt()))
                    .thenReturn(BigDecimal.ZERO);

            ProviderChatResponse response = new ProviderChatResponse(
                    "test", MODEL, PROVIDER, 10, 10, 20);

            recorder.record(1L, 1L, response, null, 0L, false);

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getMode()).isEqualTo(UsageRecorder.MODE_CHAT);
        }

        @Test
        @DisplayName("兼容旧签名 record(convId, msgId, response)")
        void shouldWorkWithOldSignature() {
            when(costCalibrationInterceptor.estimateCost(anyInt(), anyInt()))
                    .thenReturn(BigDecimal.ZERO);

            ProviderChatResponse response = new ProviderChatResponse(
                    "test", MODEL, PROVIDER, 10, 10, 20);

            recorder.record(1L, 1L, response);

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getMode()).isEqualTo(UsageRecorder.MODE_CHAT);
            assertThat(captor.getValue().getStatus()).isEqualTo(UsageRecorder.STATUS_SUCCESS);
        }
    }

    // ──────────────────────────────────────────
    // 第二部分：失败记录
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("记录失败调用 recordFailure()")
    class RecordFailureTests {

        @Test
        @DisplayName("失败调用 → status=FAILED, token=0, cost=0, Redis 记录失败")
        void shouldRecordFailure() {
            recorder.recordFailure(3L, PROVIDER, MODEL, "agent", 1200L, "timeout");

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            AiUsageLog log = captor.getValue();

            assertThat(log.getConversationId()).isEqualTo(3L);
            assertThat(log.getMessageId()).isNull();
            assertThat(log.getProvider()).isEqualTo(PROVIDER);
            assertThat(log.getMode()).isEqualTo("agent");
            assertThat(log.getPromptTokens()).isEqualTo(0);
            assertThat(log.getCompletionTokens()).isEqualTo(0);
            assertThat(log.getTotalTokens()).isEqualTo(0);
            assertThat(log.getCost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(log.getLatencyMs()).isEqualTo(1200L);
            assertThat(log.getStatus()).isEqualTo(UsageRecorder.STATUS_FAILED);

            verify(listOps).leftPush(startsWith("zhiwei:provider:metrics:window:"), contains("\"success\":false"));
        }

        @Test
        @DisplayName("失败时 mode 为空 → 默认 chat")
        void shouldDefaultModeOnFailure() {
            recorder.recordFailure(1L, PROVIDER, MODEL, null, 500L, "error");

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getMode()).isEqualTo(UsageRecorder.MODE_CHAT);
        }

        @Test
        @DisplayName("失败时 errorMsg 为 null 不抛异常")
        void shouldHandleNullErrorMessage() {
            recorder.recordFailure(1L, PROVIDER, MODEL, "chat", 100L, null);

            verify(aiUsageLogMapper).insert(ArgumentMatchers.<AiUsageLog>any());
        }
    }

    // ──────────────────────────────────────────
    // 第三部分：查询最近用量
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("查询最近用量 recent()")
    class RecentTests {

        @Test
        @DisplayName("查询返回正确数量的最近记录")
        void shouldReturnRecentItems() {
            AiUsageLog row1 = buildLog(1L, PROVIDER, MODEL, "chat", 150, BigDecimal.valueOf(0.001), 200L, "SUCCESS");
            AiUsageLog row2 = buildLog(2L, "langchain4j-openai", "qwen-turbo", "agent", 300, BigDecimal.valueOf(0.002), 500L, "DEGRADED");

            when(aiUsageLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(row1, row2));

            List<UsageRecentItem> items = recorder.recent(10);

            assertThat(items).hasSize(2);
            assertThat(items.get(0).provider()).isEqualTo(PROVIDER);
            assertThat(items.get(0).model()).isEqualTo(MODEL);
            assertThat(items.get(0).mode()).isEqualTo("chat");
            assertThat(items.get(0).totalTokens()).isEqualTo(150);
            assertThat(items.get(0).status()).isEqualTo("SUCCESS");

            assertThat(items.get(1).provider()).isEqualTo("langchain4j-openai");
            assertThat(items.get(1).status()).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("limit 超出上限 → 截断到 100")
        void shouldCapLimit() {
            when(aiUsageLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

            recorder.recent(999);

            verify(aiUsageLogMapper).selectList(argThat(qw ->
                    qw.getCustomSqlSegment() != null && qw.getCustomSqlSegment().contains("LIMIT 100")));
        }

        @Test
        @DisplayName("limit 小于 1 → 修正为 1")
        void shouldFloorLimit() {
            when(aiUsageLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

            recorder.recent(0);

            verify(aiUsageLogMapper).selectList(argThat(qw ->
                    qw.getCustomSqlSegment() != null && qw.getCustomSqlSegment().contains("LIMIT 1")));
        }

        @Test
        @DisplayName("空结果 → 返回空列表")
        void shouldReturnEmptyList() {
            when(aiUsageLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

            List<UsageRecentItem> items = recorder.recent(20);

            assertThat(items).isEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 第四部分：Redis 滑动窗口读写
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("Redis 滑动窗口")
    class RedisWindowTests {

        @Test
        @DisplayName("pushRedisSample → leftPush + trim + expire")
        void shouldPushToRedisWindow() throws Exception {
            when(costCalibrationInterceptor.estimateCost(anyInt(), anyInt()))
                    .thenReturn(BigDecimal.valueOf(0.001));

            ProviderChatResponse response = new ProviderChatResponse(
                    "test", MODEL, PROVIDER, 50, 30, 80);

            recorder.record(1L, 1L, response, "chat", 150L, false);

            verify(listOps).leftPush(eq("zhiwei:provider:metrics:window:" + PROVIDER), contains("\"success\":true"));
            verify(listOps).trim(eq("zhiwei:provider:metrics:window:" + PROVIDER), eq(0L), eq(99L));
            verify(stringRedisTemplate).expire(eq("zhiwei:provider:metrics:window:" + PROVIDER), eq(24L), eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("readRedisWindow 空窗口 → 返回空列表")
        void shouldReturnEmptyForNoSamples() {
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

            List<UsageRecorder.MetricSample> samples = recorder.readRedisWindow(PROVIDER);

            assertThat(samples).isEmpty();
        }

        @Test
        @DisplayName("readRedisWindow 正常解析 JSON 样本")
        void shouldParseRedisSamples() throws Exception {
            String json = realObjectMapper.writeValueAsString(
                    new UsageRecorder.MetricSample(true, 200L, 0.001, System.currentTimeMillis()));
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of(json));

            List<UsageRecorder.MetricSample> samples = recorder.readRedisWindow(PROVIDER);

            assertThat(samples).hasSize(1);
            assertThat(samples.get(0).success()).isTrue();
            assertThat(samples.get(0).latencyMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("readRedisWindow 解析失败 → 返回空列表")
        void shouldHandleParseErrorGracefully() {
            when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of("not-valid-json"));

            List<UsageRecorder.MetricSample> samples = recorder.readRedisWindow(PROVIDER);

            assertThat(samples).isEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 第五部分：费用计算
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("费用估算")
    class CostEstimationTests {

        @Test
        @DisplayName("费用 = prompt*单价/1000 + completion*单价/1000")
        void shouldCallCostEstimationWithCorrectTokens() {
            when(costCalibrationInterceptor.estimateCost(500, 300))
                    .thenReturn(new BigDecimal("0.005000"));

            ProviderChatResponse response = new ProviderChatResponse(
                    "test", MODEL, PROVIDER, 500, 300, 800);

            recorder.record(1L, 1L, response, "chat", 100L, false);

            verify(costCalibrationInterceptor).estimateCost(500, 300);

            ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
            verify(aiUsageLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getCost()).isEqualByComparingTo("0.005000");
        }
    }

    // ──────────────────────────────────────────
    // 第六部分：状态常量
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("状态常量")
    class StatusConstantsTests {

        @Test
        @DisplayName("三种状态值正确")
        void shouldHaveCorrectStatusValues() {
            assertThat(UsageRecorder.STATUS_SUCCESS).isEqualTo("SUCCESS");
            assertThat(UsageRecorder.STATUS_FAILED).isEqualTo("FAILED");
            assertThat(UsageRecorder.STATUS_DEGRADED).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("默认模式为 chat")
        void shouldHaveCorrectDefaultMode() {
            assertThat(UsageRecorder.MODE_CHAT).isEqualTo("chat");
        }

        @Test
        @DisplayName("Redis key 前缀正确")
        void shouldHaveCorrectRedisKeyPrefix() {
            assertThat(UsageRecorder.REDIS_WINDOW_KEY_PREFIX)
                    .isEqualTo("zhiwei:provider:metrics:window:");
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static AiUsageLog buildLog(Long id, String provider, String model,
                                        String mode, int totalTokens, BigDecimal cost,
                                        long latencyMs, String status) {
        AiUsageLog log = new AiUsageLog();
        log.setId(id);
        log.setConversationId(id * 10);
        log.setMessageId(id * 100);
        log.setProvider(provider);
        log.setModel(model);
        log.setMode(mode);
        log.setPromptTokens(totalTokens / 2);
        log.setCompletionTokens(totalTokens / 2);
        log.setTotalTokens(totalTokens);
        log.setCost(cost);
        log.setLatencyMs(latencyMs);
        log.setStatus(status);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }
}

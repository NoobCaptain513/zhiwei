package com.zihan.zhiwei.ai.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级安全约束（D22）。
 * 功能：敏感词过滤 + Prompt 注入防护 + 单用户频率限制 + 请求长度限制。
 *
 * 用法：在 ChatServiceImpl / AgentServiceImpl 的 chat 方法入口调用 check()，
 *       返回非 null 则直接拒绝，不调模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiSafetyAdvisor {

    private final SensitiveWordFilter sensitiveWordFilter;
    private final PromptInjectionDetector injectionDetector;

    /** 单用户每分钟最大请求数（防刷） */
    private static final int MAX_REQUESTS_PER_MINUTE = 30;

    /** 单条消息最大长度 */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    /** 用户请求计数器（滑动窗口简化版：按分钟统计） */
    private final Map<String, long[]> requestCounts = new ConcurrentHashMap<>();

    /**
     * 综合安全检查。
     * @param userId  用户 ID
     * @param message 用户消息
     * @return null=通过，否则=拒绝原因
     */
    public String check(String userId, String message) {
        // 1. 长度限制
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            return "消息过长，最大 " + MAX_MESSAGE_LENGTH + " 字符，当前 " + message.length();
        }

        // 2. 单用户频率限制
        String rateLimitMsg = checkRateLimit(userId);
        if (rateLimitMsg != null) return rateLimitMsg;

        // 3. 敏感词
        String sensitiveMsg = sensitiveWordFilter.check(message);
        if (sensitiveMsg != null) return sensitiveMsg;

        // 4. Prompt 注入
        String injectionMsg = injectionDetector.detect(message);
        if (injectionMsg != null) return injectionMsg;

        return null;
    }

    private String checkRateLimit(String userId) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000; // 1 分钟窗口

        long[] timestamps = requestCounts.compute(userId, (k, v) -> {
            if (v == null) return new long[]{now};
            // 清理窗口外的记录
            java.util.List<Long> valid = new java.util.ArrayList<>();
            for (long ts : v) {
                if (ts > windowStart) valid.add(ts);
            }
            valid.add(now);
            return valid.stream().mapToLong(Long::longValue).toArray();
        });

        if (timestamps.length > MAX_REQUESTS_PER_MINUTE) {
            log.warn("[Safety] rate limit exceeded userId={} count={}", userId, timestamps.length);
            return "请求过于频繁，每分钟最多 " + MAX_REQUESTS_PER_MINUTE + " 次";
        }
        return null;
    }
}
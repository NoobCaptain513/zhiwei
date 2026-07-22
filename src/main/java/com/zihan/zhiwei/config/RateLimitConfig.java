package com.zihan.zhiwei.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RateLimitConfig {

    @Bean
    public DefaultRedisScript<Long> slidingWindowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sliding-window.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate,
                                    DefaultRedisScript<Long> slidingWindowScript) {
        return new RateLimiter(redisTemplate, slidingWindowScript);
    }

    /**
     * Redis 滑动窗口限流器。
     */
    public static class RateLimiter {

        private final StringRedisTemplate redisTemplate;
        private final DefaultRedisScript<Long> script;

        private static final String KEY_PREFIX = "zhiwei:ratelimit:";
        private static final long DEFAULT_WINDOW_MS = 60_000; // 1 分钟
        private static final int DEFAULT_LIMIT = 60;

        public RateLimiter(StringRedisTemplate redisTemplate, DefaultRedisScript<Long> script) {
            this.redisTemplate = redisTemplate;
            this.script = script;
        }

        /**
         * @return true=允许, false=拒绝
         */
        public boolean allow(String identity) {
            return allow(identity, DEFAULT_LIMIT, DEFAULT_WINDOW_MS);
        }

        public boolean allow(String identity, int limit, long windowMs) {
            String key = KEY_PREFIX + identity;
            Long result = redisTemplate.execute(
                    script,
                    java.util.List.of(key),
                    String.valueOf(windowMs),
                    String.valueOf(limit),
                    String.valueOf(System.currentTimeMillis()));
            return result != null && result == 1L;
        }
    }
}
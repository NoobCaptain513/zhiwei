package com.zihan.zhiwei.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.config.RateLimitConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求级限流拦截器（D22）。
 * 按 userId（Header X-User-Id）或 IP 进行滑动窗口限流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitConfig.RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    private static final int LIMIT_PER_MINUTE = 60;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String identity = resolveIdentity(request);

        if (!rateLimiter.allow(identity, LIMIT_PER_MINUTE, 60_000)) {
            log.warn("[RateLimit] blocked identity={}", identity);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.fail(429, "请求过于频繁，请稍后再试")));
            return false;
        }
        return true;
    }

    private String resolveIdentity(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) return "user:" + userId;
        return "ip:" + request.getRemoteAddr();
    }
}
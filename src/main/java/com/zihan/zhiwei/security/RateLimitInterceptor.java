package com.zihan.zhiwei.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.config.RateLimitConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求级限流拦截器（D22）。
 * <p>
 * FIX-11: 限流身份改从 SecurityContext（JWT 认证结果）读取。
 * 优先级：已认证用户名（服务端可信）→ 客户端 IP 兜底；
 * X-User-Id 仅在 JWT 未启用的过渡期保留兼容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitConfig.RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final JwtProperties jwtProperties;

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
        // 1. 服务端认证过的身份（JWT），不可伪造
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        // 2. JWT 未启用的过渡期：兼容旧 header（明确标注不可信）
        if (!jwtProperties.isEnabled()) {
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return "user-untrusted:" + userId;
            }
        }
        // 3. IP 兜底
        return "ip:" + request.getRemoteAddr();
    }
}

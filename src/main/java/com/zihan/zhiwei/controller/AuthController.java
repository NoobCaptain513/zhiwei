package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.security.JwtProperties;
import com.zihan.zhiwei.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FIX-11: 登录签发 JWT。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtProperties jwtProperties;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (!jwtProperties.isEnabled()) {
            return Result.fail(400, "JWT 鉴权未启用");
        }
        if (req == null || req.username() == null || req.password() == null) {
            return Result.fail(400, "用户名/密码不能为空");
        }
        String encoded = jwtProperties.getUsers().get(req.username());
        if (encoded == null || !passwordEncoder.matches(req.password(), encoded)) {
            log.warn("[Auth] login failed username={}", req.username());
            return Result.fail(401, "用户名或密码错误");
        }
        String token = jwtService.issue(req.username());
        return Result.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "expiresInMinutes", jwtProperties.getTtlMinutes()));
    }
}

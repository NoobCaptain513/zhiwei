package com.zihan.zhiwei.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * FIX-11: JWT 签发与校验（jjwt 0.12.x API）。
 */
@Slf4j
@Component
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException(
                        "zhiwei.security.jwt.secret 必须 ≥ 32 字节（HS256 要求）");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        } else {
            this.key = null;
        }
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(props.getTtlMinutes() * 60)))
                .signWith(key)
                .compact();
    }

    public String verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            log.debug("[JWT] verify failed: {}", e.getMessage());
            return null;
        }
    }
}

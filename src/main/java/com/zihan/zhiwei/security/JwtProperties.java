package com.zihan.zhiwei.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FIX-11: JWT 鉴权配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "zhiwei.security.jwt")
public class JwtProperties {

    private boolean enabled = false;

    private String secret = "";

    private long ttlMinutes = 120;

    private String header = "Authorization";

    private String prefix = "Bearer ";

    /** username → 编码后密码（DelegatingPasswordEncoder 格式） */
    private Map<String, String> users = new LinkedHashMap<>();
}

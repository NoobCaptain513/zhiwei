package com.zihan.zhiwei.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全相关配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "zhiwei.security")
public class SecurityProperties {

    /**
     * 是否启用 API Key 鉴权。默认开启；未配置合法 key 时拒绝受保护请求。
     */
    private boolean apiKeyEnabled = true;

    /**
     * 合法的 API Key 列表。
     */
    private List<String> apiKeys = new ArrayList<>();

    /**
     * 请求头名称。
     */
    private String apiKeyHeader = "X-API-Key";

    /**
     * 无需鉴权的路径。
     */
    private List<String> permitAllPaths = List.of(
            "/actuator/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api/auth/**",
            "/error"
    );
}
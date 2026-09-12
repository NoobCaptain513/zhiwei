package com.zihan.zhiwei.security;

import com.zihan.zhiwei.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("安全默认值")
class SecurityDefaultsTest {

    @Test
    @DisplayName("API Key 保护默认开启并采用 fail-closed")
    void apiKeyProtectionIsEnabledByDefault() {
        SecurityProperties properties = new SecurityProperties();

        assertThat(properties.isApiKeyEnabled()).isTrue();
        assertThat(properties.getApiKeys()).isEmpty();
    }
}
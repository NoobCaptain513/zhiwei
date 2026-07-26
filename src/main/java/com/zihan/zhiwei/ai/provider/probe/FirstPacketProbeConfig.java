package com.zihan.zhiwei.ai.provider.probe;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "zhiwei.ai.probe")
public class FirstPacketProbeConfig {
    private boolean enabled = true;
    private long timeoutMs = 2000L;
    private long cacheTtlMs = 5000L;
    private int candidateLimit = 2;
}

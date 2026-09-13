package com.zihan.zhiwei.ai.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Getter @Setter @Component
@ConfigurationProperties(prefix = "zhiwei.ai.memory")
public class MemoryProperties {
    private boolean enabled;
    private boolean injectEnabled;
    private boolean factExtractionEnabled;
    private int factLimit = 10;
    private Duration checkpointTtl = Duration.ofDays(7);
    private Duration softDeleteRetention = Duration.ofDays(30);
    private int checkpointMaxBytes = 65_536;
    private final Summary summary = new Summary();
    @Getter @Setter public static class Summary {
        private int messageThreshold = 8;
        private int tokenThreshold = 3000;
        private int recentMessagesToKeep = 4;
    }
}
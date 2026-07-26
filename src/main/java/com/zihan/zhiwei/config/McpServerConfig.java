package com.zihan.zhiwei.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("mcp")
@EnableScheduling
public class McpServerConfig {
}

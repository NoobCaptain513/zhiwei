package com.zihan.zhiwei;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * D32: 独立 MCP Server 进程入口。
 * 同 JAR，不同 profile，独立端口部署。
 * 启动: java -jar zhiwei.jar --spring.profiles.active=mcp --server.port=8081
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.zihan.zhiwei",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {
                                "com\\.zihan\\.zhiwei\\.service\\.impl\\.AgentServiceImpl",
                                "com\\.zihan\\.zhiwei\\.service\\.impl\\.ChatServiceImpl"
                        }
                )
        }
)
public class ZhiweiMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ZhiweiMcpServerApplication.class);
        app.setAdditionalProfiles("mcp");
        app.run(args);
    }
}

package com.zihan.zhiwei.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 文档配置。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Zhiwei AI API")
                        .description("智维 AI 平台接口文档")
                        .version("v0.0.1")
                        .contact(new Contact().name("zhiwei").email("dev@zhiwei.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
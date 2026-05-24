package com.flashmall.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 配置
 * 访问地址：http://localhost:8080/swagger-ui/index.html
 *
 * 配置了全局 JWT Bearer 认证，在 Swagger UI 右上角点击"Authorize"，
 * 输入 "Bearer {your_token}" 后所有接口都会自动带上认证头。
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FlashMall 高并发秒杀商城 API")
                .description("Spring Boot + MySQL + Redis + RabbitMQ 秒杀系统接口文档")
                .version("1.0.0"))
            // 全局 JWT 安全认证配置
            .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
            .components(new Components()
                .addSecuritySchemes("BearerAuth", new SecurityScheme()
                    .name("BearerAuth")
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("在此输入 accessToken（登录接口返回的 accessToken 字段）")));
    }
}

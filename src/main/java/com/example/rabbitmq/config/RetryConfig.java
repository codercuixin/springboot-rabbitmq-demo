package com.example.rabbitmq.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Spring Retry 配置
 * 启用 @Retryable 和 @Recover 注解支持
 */
@Configuration
@EnableRetry
public class RetryConfig {
    // Spring Retry 会自动配置重试机制
    // 可以在这里添加自定义的 RetryTemplate 或 RetryPolicy（可选）
}


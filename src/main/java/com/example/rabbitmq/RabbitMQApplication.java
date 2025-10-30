package com.example.rabbitmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RabbitMQ Spring Boot 应用主类
 * 
 * 功能说明：
 * 1. Publisher Confirm 异步确认：消息发送到 Broker 后异步接收确认
 * 2. Consumer 异步确认：消费者异步处理消息并手动确认
 * 
 * @author CuiXin
 */
@SpringBootApplication
public class RabbitMQApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitMQApplication.class, args);
    }
}


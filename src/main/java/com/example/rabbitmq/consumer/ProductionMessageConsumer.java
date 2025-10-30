package com.example.rabbitmq.consumer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.example.rabbitmq.config.RabbitMQConfig;
import com.example.rabbitmq.model.Message;
import com.example.rabbitmq.service.MessageIdempotentService;
import com.rabbitmq.client.Channel;

import lombok.extern.slf4j.Slf4j;

/**
 * 生产级消息消费者
 * 包含完整的重试机制、幂等性处理、异常处理等
 */
@Slf4j
@Component
public class ProductionMessageConsumer {

    @Autowired
    private MessageIdempotentService idempotentService;
    
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final AtomicInteger messageCount = new AtomicInteger(0);
    
    // 最大重试次数
    private static final int MAX_RETRY_COUNT = 3;
    
    // 重试次数头部键
    private static final String RETRY_COUNT_HEADER = "x-retry-count";



    /**
     * 生产级消息消费
     * 
     * 功能特性：
     * 1. 消息幂等性检查（防止重复消费）
     * 2. 异步处理
     * 3. 自动重试（使用延迟队列）
     * 4. 失败消息进入死信队列
     * 5. 完善的异常处理和日志
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME, concurrency = "3", ackMode = "MANUAL")
    public void consumeMessage(@Payload Message message,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                               @Header(value = RETRY_COUNT_HEADER, required = false) Integer retryCount,
                               @Header(value = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        
        int count = messageCount.incrementAndGet();
        final int currentRetryCount = retryCount != null ? retryCount : 0;
        
        log.info("← [Production Consumer] 收到消息 #{}: ID={}, Content={}, RetryCount={}/{}", 
                count, message.getId(), message.getContent(), currentRetryCount, MAX_RETRY_COUNT);
        
        // 1. 幂等性检查
        if (idempotentService.isDuplicate(message.getId())) {
            log.warn("⚠ [Production Consumer] 检测到重复消息，直接确认: ID={}", message.getId());
            try {
                channel.basicAck(deliveryTag, false);
            } catch (IOException e) {
                log.error("✗ [Production Consumer] 确认重复消息失败: ", e);
            }
            return;
        }
        
        // 2.处理消息
        try {
            // 业务处理
            processBusinessLogic(message);
            
            // 标记为已处理（幂等性）
            idempotentService.markAsProcessed(message.getId());
            
            // 确认消息
            channel.basicAck(deliveryTag, false);
            log.info("✓ [Production Consumer] 消息处理成功并确认: ID={}", message.getId());
            
        } catch (Exception e) {
            log.error("✗ [Production Consumer] 消息处理失败: ID={}, RetryCount={}, Error={}", 
                    message.getId(), currentRetryCount, e.getMessage());         
            // 处理失败
            handleMessageFailure(channel, deliveryTag, message, currentRetryCount);
        }
    }

    /**
     * 业务逻辑处理
     */
    private void processBusinessLogic(Message message) throws Exception {
        log.info("→ [Production Consumer] 处理业务逻辑: {}", message.getContent());
        
        // 模拟耗时操作
        Thread.sleep(1000);
        
        // 模拟业务逻辑
        if (message.getContent().toLowerCase().contains("error")) {
            throw new RuntimeException("业务处理失败：消息内容包含错误标识");
        }
        
        // 模拟其他业务异常
        if (message.getContent().toLowerCase().contains("exception")) {
            throw new IllegalStateException("业务异常：非法状态");
        }
        
        log.info("✓ [Production Consumer] 业务逻辑处理完成: {}", message.getContent());
    }

    /**
     * 处理消息失败
     * 支持自动重试和死信队列
     */
    private void handleMessageFailure(Channel channel, long deliveryTag, 
                                     Message message, int retryCount) {
        try {
            if (retryCount < MAX_RETRY_COUNT) {
                // 还可以重试，发送到延迟重试队列
                sendToRetryQueue(message, retryCount + 1);
                
                // 确认当前消息（因为已经发送到重试队列了）
                channel.basicAck(deliveryTag, false);
                
                log.warn("⟳ [Production Consumer] 消息已发送到重试队列: ID={}, RetryCount={}/{}", 
                        message.getId(), retryCount + 1, MAX_RETRY_COUNT);
                
            } else {
                // 达到最大重试次数，拒绝消息并发送到死信队列
                channel.basicNack(deliveryTag, false, false);
                
                log.error("☠ [Production Consumer] 消息达到最大重试次数，已发送到死信队列: ID={}", 
                        message.getId());
            }
        } catch (IOException e) {
            log.error("✗ [Production Consumer] 处理失败消息时发生异常: ", e);
        }
    }

    /**
     * 发送消息到延迟重试队列
     */
    private void sendToRetryQueue(Message message, int retryCount) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.RETRY_EXCHANGE_NAME,
                    RabbitMQConfig.RETRY_ROUTING_KEY,
                    message,
                    msg -> {
                        // 设置重试次数
                        msg.getMessageProperties().setHeader(RETRY_COUNT_HEADER, retryCount);
                        return msg;
                    }
            );
            
            log.info("→ [Production Consumer] 消息已发送到延迟重试队列: ID={}, RetryCount={}, Delay={}ms", 
                    message.getId(), retryCount, RabbitMQConfig.RETRY_DELAY_MS);
                    
        } catch (Exception e) {
            log.error("✗ [Production Consumer] 发送到重试队列失败: ", e);
        }
    }
}


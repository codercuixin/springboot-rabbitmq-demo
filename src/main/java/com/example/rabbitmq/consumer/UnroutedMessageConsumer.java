package com.example.rabbitmq.consumer;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.rabbitmq.config.RabbitMQConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * 未路由消息消费者
 * 消费从 Alternate Exchange 路由过来的失败消息
 * 
 * 工作原理：
 * 1. 当主交换机无法路由消息时，消息会自动转发到 Alternate Exchange
 * 2. Alternate Exchange 将消息路由到 unrouted.messages.queue
 * 3. 这个消费者监听该队列，处理所有路由失败的消息
 * 
 * 处理策略：
 * - 记录详细的失败信息
 * - 可以触发告警
 * - 可以尝试修复路由键后重发
 * - 可以持久化到数据库供人工处理
 */
@Slf4j
@Component
public class UnroutedMessageConsumer {

    /**
     * 消费未路由的消息
     * 
     * @param message 原始消息对象（包含消息体、属性、头部信息等）
     */
    @RabbitListener(queues = RabbitMQConfig.UNROUTED_QUEUE_NAME)
    public void handleUnroutedMessage(Message message) {
        try {
            String messageBody = new String(message.getBody());
            
            log.warn("⚠ ========== 收到未路由消息 ==========");
            log.warn("消息内容: {}", messageBody);
            log.warn("消息ID: {}", message.getMessageProperties().getMessageId());
            log.warn("原始交换机: {}", message.getMessageProperties().getReceivedExchange());
            log.warn("原始路由键: {}", message.getMessageProperties().getReceivedRoutingKey());
            log.warn("时间戳: {}", message.getMessageProperties().getTimestamp());
            log.warn("=====================================");
            
            // TODO: 生产环境的处理策略
            
            // 策略1: 分析路由键，尝试修复后重发
            // analyzeAndRetry(message);
            
            // 策略2: 持久化到数据库，供人工处理
            // persistForManualHandling(message);
            
            // 策略3: 发送告警通知
            // sendAlert(message);
            
            // 策略4: 根据业务规则决定是否需要补偿操作
            // applyCompensation(message);
            
        } catch (Exception e) {
            log.error("✗ 处理未路由消息异常: ", e);
            // 这里的异常处理要特别小心，不能让消息再次进入死循环
            // 建议：记录到错误日志，人工介入处理
        }
    }
    
    /**
     * 分析并尝试修复路由键
     * 示例实现（根据实际业务调整）
     */
    @SuppressWarnings("unused")
    private void analyzeAndRetry(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        
        // 示例：如果路由键格式错误，尝试修复
        if (routingKey != null && routingKey.contains("wrong")) {
            String correctedRoutingKey = routingKey.replace("wrong", RabbitMQConfig.ROUTING_KEY);
            log.info("→ 尝试修复路由键: {} -> {}", routingKey, correctedRoutingKey);
            // 重新发送消息（需要注入 RabbitTemplate）
        }
    }
    
    /**
     * 持久化到数据库供人工处理
     * 示例实现（根据实际业务调整）
     */
    @SuppressWarnings("unused")
    private void persistForManualHandling(Message message) {
        // 将消息保存到数据库
        // failedMessageRepository.save(...)
        log.info("✓ 未路由消息已持久化到数据库");
    }
    
    /**
     * 发送告警
     * 示例实现（根据实际业务调整）
     */
    @SuppressWarnings("unused")
    private void sendAlert(Message message) {
        String alertMessage = String.format(
            "【RabbitMQ告警】收到未路由消息\n" +
            "交换机: %s\n" +
            "路由键: %s\n" +
            "消息ID: %s",
            message.getMessageProperties().getReceivedExchange(),
            message.getMessageProperties().getReceivedRoutingKey(),
            message.getMessageProperties().getMessageId()
        );
        
        log.warn("⚠ 告警: {}", alertMessage);
        // 实际项目中调用告警服务
        // alertService.send(alertMessage);
    }
}


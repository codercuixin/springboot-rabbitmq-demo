package com.example.rabbitmq.consumer;

import java.util.Map;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.example.rabbitmq.config.RabbitMQConfig;
import com.example.rabbitmq.model.Message;
import com.example.rabbitmq.service.MessageFailureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import lombok.extern.slf4j.Slf4j;

/**
 * 死信队列消费者
 * 处理消费失败的消息
 * 
 * 功能：
 * 1. 记录死信消息到统一的失败消息服务
 * 2. 发送告警通知
 * 3. 支持后续人工处理和重试
 */
@Slf4j
@Component
public class DeadLetterQueueConsumer {

    @Autowired
    private MessageFailureService messageFailureService;
    
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 监听死信队列
     * 可以在这里进行：
     * 1. 记录失败消息到数据库
     * 2. 发送告警通知
     * 3. 人工介入处理
     * 4. 转发到其他系统
     */
    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE_NAME)
    public void handleDeadLetter(@Payload Message message,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                 @Header(value = "x-death", required = false) java.util.List<Map<String, Object>> xDeath) {
        try {
            log.error("☠ [DLX Consumer] 收到死信消息: ID={}, Content={}", 
                    message.getId(), message.getContent());
            
            // 打印死信详细信息
            if (xDeath != null && !xDeath.isEmpty()) {
                Map<String, Object> death = xDeath.get(0);
                log.error("☠ [DLX Consumer] 死信详情: " +
                        "\n  原因: {}" +
                        "\n  队列: {}" +
                        "\n  交换机: {}" +
                        "\n  路由键: {}" +
                        "\n  次数: {}",
                        death.get("reason"),
                        death.get("queue"),
                        death.get("exchange"),
                        death.get("routing-keys"),
                        death.get("count"));
            }
            
            // 业务处理
            // 1. 记录到统一的失败消息服务
            // 2. 发送告警
            // 3. 其他补偿操作
            saveFailedMessageToService(message, xDeath);
            sendAlertNotification(message);
            
            // 确认死信消息
            channel.basicAck(deliveryTag, false);
            log.info("✓ [DLX Consumer] 死信消息已处理并确认");
            
        } catch (Exception e) {
            log.error("✗ [DLX Consumer] 处理死信消息失败: ", e);
            
            try {
                // 拒绝消息，但不重新入队（避免死循环）
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("✗ [DLX Consumer] 拒绝死信消息失败: ", ex);
            }
        }
    }

    /**
     * 保存失败消息到统一的失败消息服务
     */
    private void saveFailedMessageToService(Message message, java.util.List<Map<String, Object>> xDeath) {
        try {
            // 提取死信详细信息
            String reason = "UNKNOWN";
            String queue = "UNKNOWN";
            String exchange = "UNKNOWN";
            String routingKey = "UNKNOWN";
            int replyCode = 0;
            
            if (xDeath != null && !xDeath.isEmpty()) {
                Map<String, Object> death = xDeath.get(0);
                reason = death.get("reason") != null ? death.get("reason").toString() : "UNKNOWN";
                queue = death.get("queue") != null ? death.get("queue").toString() : "UNKNOWN";
                exchange = death.get("exchange") != null ? death.get("exchange").toString() : "UNKNOWN";
                
                Object routingKeys = death.get("routing-keys");
                if (routingKeys != null) {
                    routingKey = routingKeys.toString().replaceAll("[\\[\\]]", "");
                }
            }
            
            // 将消息对象转为 JSON 字符串
            String messageBody = objectMapper.writeValueAsString(message);
            
            // 确定失败类型
            String failureType = "CONSUME_FAILED";
            if ("rejected".equals(reason)) {
                failureType = "BUSINESS_ERROR";  // 业务处理失败
            } else if ("expired".equals(reason)) {
                failureType = "MESSAGE_EXPIRED";  // 消息过期
            } else if ("maxlen".equals(reason)) {
                failureType = "QUEUE_OVERFLOW";  // 队列溢出
            }
            
            // 保存到统一的失败消息服务
            String failedMessageId = messageFailureService.saveFailedMessage(
                messageBody,
                exchange,
                routingKey,
                replyCode,
                String.format("死信原因: %s, 来源队列: %s", reason, queue),
                failureType,
                "CONSUME"  // 消费阶段失败
            );
            
            log.info("✓ [DLX Consumer] 死信消息已保存到失败消息服务，ID: {}", failedMessageId);
            
        } catch (Exception e) {
            log.error("✗ [DLX Consumer] 保存失败消息异常: ", e);
        }
    }

    /**
     * 发送告警通知
     */
    private void sendAlertNotification(Message message) {
        // TODO: 实现告警通知逻辑（邮件、短信、钉钉等）
        log.warn("⚠ [DLX Consumer] 发送告警通知: 消息处理失败 ID={}", message.getId());
    }
}


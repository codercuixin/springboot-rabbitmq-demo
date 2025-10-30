package com.example.rabbitmq.publisher;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import com.example.rabbitmq.config.RabbitMQConfig;
import com.example.rabbitmq.model.Message;
import com.example.rabbitmq.service.MessageFailureService;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息发布者
 * 实现 Publisher Confirm 异步确认机制
 */
@Slf4j
@Component
public class MessagePublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Autowired
    private MessageFailureService messageFailureService;

    /**
     * 初始化回调配置
     * 配置 Publisher Confirm 和 Return 回调
     */
    @PostConstruct
    public void init() {
        // 配置 Confirm 回调：当消息到达 Exchange 时触发
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData != null) {
                String messageId = correlationData.getId();
                
                if (ack) {
                    log.info("✓ [Publisher Confirm] 消息发送成功！消息ID: {}", messageId);
                    
                    // 如果使用了 CorrelationData 的 Future，可以在这里设置结果
                    CompletableFuture<CorrelationData.Confirm> future = correlationData.getFuture();
                    if (future != null && !future.isDone()) {
                        future.complete(new CorrelationData.Confirm(ack, cause));
                    }
                } else {
                    log.error("✗ [Publisher Confirm] 消息发送失败！消息ID: {}, 原因: {}", 
                            messageId, cause);
                    // 消息未到达 Exchange，需要处理
                    handleConfirmFailure(correlationData, cause);
                }
            }
        });

        // 配置 Return 回调：当消息无法路由到队列时触发
        // 生产级别的处理方案：持久化 + 告警 + 重试
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("✗ [Publisher Return] 消息路由失败！" +
                    "\n  消息: {}" +
                    "\n  响应码: {}" +
                    "\n  响应信息: {}" +
                    "\n  交换机: {}" +
                    "\n  路由键: {}",
                    returned.getMessage(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    returned.getExchange(),
                    returned.getRoutingKey()
            );
            
            // 【生产方案1】持久化失败消息，供后续重试或人工处理
            try {
                // 提取消息内容
                org.springframework.amqp.core.Message message = returned.getMessage();
                String messageBody = new String(message.getBody());
                
                // 记录失败信息到存储（数据库/Redis等）
                String failedMessageId = messageFailureService.saveFailedMessage(
                    messageBody,
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    "ROUTING_FAILED",
                    "PUBLISH"  // 发送阶段失败
                );
                
                log.info("✓ 失败消息已持久化，ID: {}，可通过管理接口重试", failedMessageId);
                
            } catch (Exception e) {
                log.error("✗ 保存失败消息异常（这是严重问题！）: ", e);
                // 这里的异常处理很关键，不能让它影响主流程
                // 实际项目中应该有兜底方案，比如写入本地文件
            }
            
            // 【生产方案2】发送到备用队列（如果配置了 Alternate Exchange 会自动处理）
            // 这里可以选择性发送到专门的失败消息队列
            // 注释掉的代码：
            // try {
            //     rabbitTemplate.convertAndSend(
            //         "failed.message.exchange",
            //         "failed.message.routing.key",
            //         returned.getMessage()
            //     );
            //     log.info("✓ 失败消息已转发到备用队列");
            // } catch (Exception e) {
            //     log.error("✗ 转发到备用队列失败: ", e);
            // }
            
            // 【生产方案3】触发告警（实际项目中需要集成告警服务）
            try {
                // TODO: 集成告警服务（钉钉、企业微信、邮件等）
                String alertMessage = String.format(
                    "【RabbitMQ告警】消息路由失败\n" +
                    "交换机: %s\n" +
                    "路由键: %s\n" +
                    "响应码: %d\n" +
                    "原因: %s\n" +
                    "时间: %s",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    java.time.LocalDateTime.now()
                );
                
                log.warn("⚠ 告警信息: {}", alertMessage);
                
                // 实际项目中应该调用告警服务
                // alertService.sendDingTalkAlert(alertMessage);
                // alertService.sendEmail(alertMessage);
                
            } catch (Exception e) {
                log.error("✗ 发送告警失败: ", e);
            }
        });
        
        log.info("Publisher Confirm 回调配置完成");
    }

    /**
     * 处理 Confirm 失败（消息未到达 Exchange）
     * 
     * 注意：如果使用 sendMessageWithRetry() 方法，临时网络问题会自动重试，不会触发这个回调。
     * 这个方法主要处理以下情况：
     * 1. Exchange 不存在（配置错误）
     * 2. RabbitMQ 权限问题
     * 3. Exchange 类型配置错误
     * 
     * 这些都是配置问题，不应该重试，应该立即告警并人工修复。
     */
    private void handleConfirmFailure(CorrelationData correlationData, String cause) {
        try {
            String messageId = correlationData.getId();
            
            log.error("✗ [Publisher Confirm] 消息未到达 Exchange（可能是配置问题）: {}, 原因: {}", 
                    messageId, cause);
            
            // 记录 Confirm 失败的消息（通常是配置错误，不是临时网络问题）
            messageFailureService.saveFailedMessage(
                messageId,
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY,
                0,
                cause != null ? cause : "消息未到达Exchange（可能是配置问题）",
                "CONFIRM_FAILED",
                "PUBLISH"  // 发送阶段失败
            );
            
            log.warn("⚠ Confirm失败消息已记录，消息ID: {}，请检查 Exchange 配置", messageId);
            
            // TODO: 发送告警通知
            // alertService.sendAlert("Confirm失败（可能是配置问题）", "消息ID: " + messageId + ", 原因: " + cause);
            
        } catch (Exception e) {
            log.error("✗ 保存 Confirm 失败消息异常: ", e);
        }
    }

    /**
     * 发送消息（带异步确认）- 无重试版本
     * 适用于不需要自动重试的场景
     * 
     * @param message 要发送的消息
     * @return CorrelationData 可用于跟踪消息确认状态
     */
    public CorrelationData sendMessage(Message message) {
        // 创建唯一的消息ID
        String messageId = UUID.randomUUID().toString();
        
        // 创建 CorrelationData，用于关联消息和确认回调
        CorrelationData correlationData = new CorrelationData(messageId);
        
        try {
            log.info("→ [Publisher] 准备发送消息: {}, ID: {}", message.getContent(), messageId);
            
            // 发送消息到指定的交换机和路由键
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message,
                    correlationData
            );
            
            log.info("→ [Publisher] 消息已发送，等待 Broker 确认...");
            
        } catch (Exception e) {
            log.error("✗ [Publisher] 发送消息异常: ", e);
        }
        
        return correlationData;
    }

    /**
     * 发送消息（带自动重试）- 推荐使用
     * 处理临时网络问题：自动重试3次，使用指数退避策略
     * 重试策略：第1次立即，第2次等1秒，第3次等2秒，第4次等4秒
     * 
     * @param message 要发送的消息
     * @return 消息ID（成功时），null（失败时）
     */
    @Retryable(
        retryFor = { AmqpException.class, Exception.class },
        maxAttempts = 4,  // 1次初始尝试 + 3次重试
        backoff = @Backoff(
            delay = 1000,        // 初始延迟 1 秒
            multiplier = 2.0,    // 每次延迟翻倍
            maxDelay = 10000     // 最大延迟 10 秒
        )
    )
    public String sendMessageWithRetry(Message message) {
        // 创建唯一的消息ID
        String messageId = UUID.randomUUID().toString();
        
        // 创建 CorrelationData，用于关联消息和确认回调
        CorrelationData correlationData = new CorrelationData(messageId);
        
        log.info("→ [Resilient Publisher] 发送消息（带重试）: {}, ID: {}", message.getContent(), messageId);
        
        try {
            // 发送消息到指定的交换机和路由键
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message,
                    correlationData
            );
            
            log.info("✓ [Resilient Publisher] 消息发送成功: {}", messageId);
            return messageId;
            
        } catch (AmqpException e) {
            log.warn("⟳ [Resilient Publisher] 发送消息失败（将重试）: {}, 原因: {}", 
                    messageId, e.getMessage());
            throw e;  // 抛出异常触发重试
        }
    }

    /**
     * Recover 方法：当所有重试都失败后调用
     * 这里会保存失败消息到 MessageFailureService，供后续手动处理
     * 
     * @param e 最后一次失败的异常
     * @param message 原始消息
     * @return null 表示最终失败
     */
    @Recover
    public String recoverFromSendFailure(Exception e, Message message) {
        log.error("✗ [Resilient Publisher] 消息发送失败（重试已用尽）: {}, 错误: {}", 
                message.getContent(), e.getMessage());
        
        try {
            // 保存失败消息到持久化存储
            String failedMessageId = messageFailureService.saveFailedMessage(
                message.getContent(),
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY,
                0,
                "重试" + 3 + "次后仍然失败: " + e.getMessage(),
                "SEND_FAILED_AFTER_RETRY",
                "PUBLISH"  // 发送阶段失败
            );
            
            log.warn("⚠ 失败消息已保存，ID: {}，可通过管理接口重试", failedMessageId);
            
            // TODO: 发送告警通知运维人员
            // alertService.sendUrgentAlert("消息发送失败（重试3次后）", message.getContent(), e.getMessage());
            
            return null;  // 返回 null 表示失败
            
        } catch (Exception saveException) {
            log.error("✗ 保存失败消息异常（严重问题！）: ", saveException);
            return null;
        }
    }

    /**
     * 发送消息并等待确认结果（同步等待异步确认）
     * 
     * @param message 要发送的消息
     * @return 是否发送成功
     */
    public boolean sendMessageWithConfirm(Message message) {
        CorrelationData correlationData = sendMessage(message);
        
        try {
            // 等待异步确认结果（最多等待10秒）
            CompletableFuture<CorrelationData.Confirm> future = correlationData.getFuture();
            CorrelationData.Confirm confirm = future.get(10, TimeUnit.SECONDS);
            
            if (confirm.isAck()) {
                log.info("✓ [Publisher] 收到确认，消息发送成功");
                return true;
            } else {
                log.error("✗ [Publisher] 收到拒绝，消息发送失败: {}", confirm.getReason());
                return false;
            }
        } catch (Exception e) {
            log.error("✗ [Publisher] 等待确认超时或异常: ", e);
            return false;
        }
    }

    /**
     * 批量发送消息
     * 
     * @param messages 消息列表
     */
    public void sendBatchMessages(java.util.List<Message> messages) {
        log.info("→ [Publisher] 开始批量发送 {} 条消息", messages.size());
        
        messages.forEach(this::sendMessage);
        
        log.info("→ [Publisher] 批量发送完成，等待异步确认...");
    }

    /**
     * 测试发送到错误的路由键（触发 Return 回调）
     */
    public void sendMessageToWrongRoutingKey(Message message) {
        String messageId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(messageId);
        
        log.info("→ [Publisher] 发送消息到错误的路由键（测试 Return 回调）");
        
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                "wrong.routing.key",  // 错误的路由键
                message,
                correlationData
        );
    }
}


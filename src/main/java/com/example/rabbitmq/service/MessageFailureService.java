package com.example.rabbitmq.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息失败处理服务（统一管理）
 * 负责持久化、重试、管理失败的消息
 * 
 * 职责范围：
 * 1. 发送阶段失败（PUBLISH）：路由失败、Confirm失败
 * 2. 消费阶段失败（CONSUME）：业务处理失败、死信消息
 * 
 * 生产环境建议：
 * 1. 使用数据库持久化（MySQL、PostgreSQL等）
 * 2. 添加定时任务自动重试
 * 3. 集成告警系统（钉钉、企业微信、邮件等）
 */
@Slf4j
@Service
public class MessageFailureService {
    
    // 简单示例：使用内存存储（生产环境应该用数据库）
    // TODO: 生产环境替换为数据库存储
    private final Map<String, FailedMessage> failedMessages = new ConcurrentHashMap<>();
    
    /**
     * 保存失败的消息
     * 
     * @param messageBody 消息内容
     * @param exchange 交换机名称
     * @param routingKey 路由键
     * @param replyCode 响应码
     * @param replyText 响应信息
     * @param failureType 失败类型（ROUTING_FAILED, CONFIRM_FAILED, CONSUME_FAILED, BUSINESS_ERROR等）
     * @param failureStage 失败阶段（PUBLISH=发送失败, CONSUME=消费失败）
     * @return 失败消息ID
     */
    public String saveFailedMessage(
            String messageBody,
            String exchange,
            String routingKey,
            int replyCode,
            String replyText,
            String failureType,
            String failureStage
    ) {
        String id = UUID.randomUUID().toString();
        
        FailedMessage failedMessage = FailedMessage.builder()
                .id(id)
                .messageBody(messageBody)
                .exchange(exchange)
                .routingKey(routingKey)
                .replyCode(replyCode)
                .replyText(replyText)
                .failureType(failureType)
                .failureStage(failureStage)
                .failureTime(LocalDateTime.now())
                .retryCount(0)
                .maxRetryCount(3)
                .status("PENDING")
                .build();
        
        failedMessages.put(id, failedMessage);
        
        log.info("✓ 失败消息已保存，ID: {}, 阶段: {}, 类型: {}, 原因: {}", 
                id, failureStage, failureType, replyText);
        
        // TODO: 生产环境这里应该保存到数据库
        // failedMessageRepository.save(failedMessage);
        
        return id;
    }
    
    /**
     * 获取失败消息详情
     */
    public FailedMessage getFailedMessage(String messageId) {
        return failedMessages.get(messageId);
    }
    
    /**
     * 获取所有失败的消息
     */
    public List<FailedMessage> getAllFailedMessages() {
        return new ArrayList<>(failedMessages.values());
    }
    
    /**
     * 获取待处理的失败消息（按失败时间排序）
     */
    public List<FailedMessage> getPendingFailedMessages() {
        return failedMessages.values().stream()
                .filter(msg -> "PENDING".equals(msg.getStatus()))
                .sorted((a, b) -> a.getFailureTime().compareTo(b.getFailureTime()))
                .collect(Collectors.toList());
    }
    
    /**
     * 按失败阶段获取失败消息
     * 
     * @param failureStage 失败阶段（PUBLISH / CONSUME）
     */
    public List<FailedMessage> getFailedMessagesByStage(String failureStage) {
        return failedMessages.values().stream()
                .filter(msg -> failureStage.equals(msg.getFailureStage()))
                .sorted((a, b) -> b.getFailureTime().compareTo(a.getFailureTime()))
                .collect(Collectors.toList());
    }
    
    /**
     * 按失败阶段和状态获取失败消息
     * 
     * @param failureStage 失败阶段（PUBLISH / CONSUME）
     * @param status 消息状态
     */
    public List<FailedMessage> getFailedMessagesByStageAndStatus(String failureStage, String status) {
        return failedMessages.values().stream()
                .filter(msg -> failureStage.equals(msg.getFailureStage()) && status.equals(msg.getStatus()))
                .sorted((a, b) -> b.getFailureTime().compareTo(a.getFailureTime()))
                .collect(Collectors.toList());
    }
    
    /**
     * 标记消息为重试中
     */
    public void markAsRetrying(String messageId) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            message.setStatus("RETRYING");
            message.setRetryCount(message.getRetryCount() + 1);
            message.setLastRetryTime(LocalDateTime.now());
            log.info("→ 消息标记为重试中，ID: {}, 重试次数: {}", messageId, message.getRetryCount());
        }
    }
    
    /**
     * 标记消息为重试成功
     */
    public void markAsRetrySuccess(String messageId) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            message.setStatus("RETRY_SUCCESS");
            message.setResolvedTime(LocalDateTime.now());
            log.info("✓ 消息重试成功，ID: {}", messageId);
        }
    }
    
    /**
     * 标记消息为重试失败
     */
    public void markAsRetryFailed(String messageId, String reason) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            if (message.getRetryCount() >= message.getMaxRetryCount()) {
                message.setStatus("RETRY_EXHAUSTED");
                log.error("✗ 消息重试次数已用尽，ID: {}, 需要人工处理", messageId);
            } else {
                message.setStatus("PENDING");
                log.warn("⚠ 消息重试失败，ID: {}, 原因: {}, 剩余重试次数: {}", 
                        messageId, reason, message.getMaxRetryCount() - message.getRetryCount());
            }
        }
    }
    
    /**
     * 手动标记消息为已处理
     */
    public void markAsManuallyResolved(String messageId, String resolvedBy, String note) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            message.setStatus("MANUALLY_RESOLVED");
            message.setResolvedTime(LocalDateTime.now());
            message.setResolvedBy(resolvedBy);
            message.setNote(note);
            log.info("✓ 消息已人工处理，ID: {}, 处理人: {}", messageId, resolvedBy);
        }
    }
    
    /**
     * 删除失败消息记录
     */
    public boolean deleteFailedMessage(String messageId) {
        FailedMessage removed = failedMessages.remove(messageId);
        if (removed != null) {
            log.info("✓ 失败消息已删除，ID: {}", messageId);
            return true;
        }
        return false;
    }
    
    /**
     * 获取失败消息统计
     */
    public FailureStatistics getStatistics() {
        long total = failedMessages.size();
        long pending = failedMessages.values().stream().filter(m -> "PENDING".equals(m.getStatus())).count();
        long retrying = failedMessages.values().stream().filter(m -> "RETRYING".equals(m.getStatus())).count();
        long retrySuccess = failedMessages.values().stream().filter(m -> "RETRY_SUCCESS".equals(m.getStatus())).count();
        long retryExhausted = failedMessages.values().stream().filter(m -> "RETRY_EXHAUSTED".equals(m.getStatus())).count();
        long manuallyResolved = failedMessages.values().stream().filter(m -> "MANUALLY_RESOLVED".equals(m.getStatus())).count();
        
        // 按阶段统计
        long publishFailures = failedMessages.values().stream().filter(m -> "PUBLISH".equals(m.getFailureStage())).count();
        long consumeFailures = failedMessages.values().stream().filter(m -> "CONSUME".equals(m.getFailureStage())).count();
        
        return FailureStatistics.builder()
                .total(total)
                .pending(pending)
                .retrying(retrying)
                .retrySuccess(retrySuccess)
                .retryExhausted(retryExhausted)
                .manuallyResolved(manuallyResolved)
                .publishFailures(publishFailures)
                .consumeFailures(consumeFailures)
                .build();
    }
    
    /**
     * 按阶段获取失败消息统计
     * 
     * @param failureStage 失败阶段（PUBLISH / CONSUME）
     */
    public FailureStatistics getStatisticsByStage(String failureStage) {
        List<FailedMessage> messages = failedMessages.values().stream()
                .filter(m -> failureStage.equals(m.getFailureStage()))
                .collect(Collectors.toList());
        
        long total = messages.size();
        long pending = messages.stream().filter(m -> "PENDING".equals(m.getStatus())).count();
        long retrying = messages.stream().filter(m -> "RETRYING".equals(m.getStatus())).count();
        long retrySuccess = messages.stream().filter(m -> "RETRY_SUCCESS".equals(m.getStatus())).count();
        long retryExhausted = messages.stream().filter(m -> "RETRY_EXHAUSTED".equals(m.getStatus())).count();
        long manuallyResolved = messages.stream().filter(m -> "MANUALLY_RESOLVED".equals(m.getStatus())).count();
        
        return FailureStatistics.builder()
                .total(total)
                .pending(pending)
                .retrying(retrying)
                .retrySuccess(retrySuccess)
                .retryExhausted(retryExhausted)
                .manuallyResolved(manuallyResolved)
                .publishFailures("PUBLISH".equals(failureStage) ? total : 0)
                .consumeFailures("CONSUME".equals(failureStage) ? total : 0)
                .build();
    }
    
    /**
     * 失败消息实体
     * 生产环境建议使用数据库实体类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedMessage {
        private String id;
        private String messageBody;
        private String exchange;
        private String routingKey;
        private int replyCode;
        private String replyText;
        
        /** 失败阶段：PUBLISH=发送失败, CONSUME=消费失败 */
        private String failureStage;
        
        /** 
         * 失败类型：
         * - PUBLISH阶段: ROUTING_FAILED（路由失败）, CONFIRM_FAILED（确认失败）
         * - CONSUME阶段: CONSUME_FAILED（消费失败）, BUSINESS_ERROR（业务错误）
         */
        private String failureType;
        
        private LocalDateTime failureTime;
        private int retryCount;
        private int maxRetryCount;
        private LocalDateTime lastRetryTime;
        
        /** 消息状态：PENDING, RETRYING, RETRY_SUCCESS, RETRY_EXHAUSTED, MANUALLY_RESOLVED */
        private String status;
        
        private LocalDateTime resolvedTime;
        private String resolvedBy;
        private String note;
    }
    
    /**
     * 失败消息统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureStatistics {
        /** 总数 */
        private long total;
        
        /** 待处理 */
        private long pending;
        
        /** 重试中 */
        private long retrying;
        
        /** 重试成功 */
        private long retrySuccess;
        
        /** 重试耗尽 */
        private long retryExhausted;
        
        /** 人工处理 */
        private long manuallyResolved;
        
        /** 发送阶段失败数量 */
        private long publishFailures;
        
        /** 消费阶段失败数量 */
        private long consumeFailures;
    }
}


package com.example.rabbitmq.service;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 消息幂等性服务
 * 用于防止消息重复消费
 * 
 * 生产环境建议使用 Redis 实现
 */
@Slf4j
@Service
public class MessageIdempotentService {

    // 使用内存缓存（生产环境应该使用 Redis）
    private final com.google.common.cache.Cache<String, Boolean> processedMessages = 
            com.google.common.cache.CacheBuilder.newBuilder()
                    .expireAfterWrite(1, TimeUnit.HOURS)  // 1小时后过期
                    .maximumSize(10000)  // 最多缓存10000条
                    .build();

    /**
     * 检查消息是否已处理
     * 
     * @param messageId 消息ID
     * @return true=已处理, false=未处理
     */
    public boolean isDuplicate(String messageId) {
        Boolean processed = processedMessages.getIfPresent(messageId);
        boolean isDuplicate = processed != null && processed;
        
        if (isDuplicate) {
            log.warn("⚠ [Idempotent] 检测到重复消息: ID={}", messageId);
        }
        
        return isDuplicate;
    }

    /**
     * 标记消息为已处理
     * 
     * @param messageId 消息ID
     */
    public void markAsProcessed(String messageId) {
        processedMessages.put(messageId, true);
        log.debug("→ [Idempotent] 标记消息已处理: ID={}", messageId);
    }

    /**
     * 移除消息标记（用于处理失败时）
     * 
     * @param messageId 消息ID
     */
    public void removeProcessed(String messageId) {
        processedMessages.invalidate(messageId);
        log.debug("→ [Idempotent] 移除消息标记: ID={}", messageId);
    }

    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        var stats = processedMessages.stats();
        return String.format("缓存统计: 大小=%d, 命中率=%.2f%%, 驱逐数=%d",
                processedMessages.size(),
                stats.hitRate() * 100,
                stats.evictionCount());
    }
}


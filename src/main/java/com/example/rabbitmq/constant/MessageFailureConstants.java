package com.example.rabbitmq.constant;

/**
 * 消息失败相关常量定义
 * 
 * 统一管理失败阶段、失败类型、消息状态等常量
 */
public class MessageFailureConstants {
    
    /**
     * 失败阶段
     */
    public static class FailureStage {
        /** 发送阶段失败 */
        public static final String PUBLISH = "PUBLISH";
        
        /** 消费阶段失败 */
        public static final String CONSUME = "CONSUME";
    }
    
    /**
     * 失败类型 - 发送阶段
     */
    public static class PublishFailureType {
        /** 路由失败 - 消息无法路由到任何队列 */
        public static final String ROUTING_FAILED = "ROUTING_FAILED";
        
        /** Confirm失败 - 消息未到达Exchange */
        public static final String CONFIRM_FAILED = "CONFIRM_FAILED";
    }
    
    /**
     * 失败类型 - 消费阶段
     */
    public static class ConsumeFailureType {
        /** 消费失败 - 通用消费失败 */
        public static final String CONSUME_FAILED = "CONSUME_FAILED";
        
        /** 业务错误 - 业务逻辑处理失败 */
        public static final String BUSINESS_ERROR = "BUSINESS_ERROR";
        
        /** 消息过期 - 消息TTL超时 */
        public static final String MESSAGE_EXPIRED = "MESSAGE_EXPIRED";
        
        /** 队列溢出 - 队列长度超过限制 */
        public static final String QUEUE_OVERFLOW = "QUEUE_OVERFLOW";
    }
    
    /**
     * 消息状态
     */
    public static class MessageStatus {
        /** 待处理 */
        public static final String PENDING = "PENDING";
        
        /** 重试中 */
        public static final String RETRYING = "RETRYING";
        
        /** 重试成功 */
        public static final String RETRY_SUCCESS = "RETRY_SUCCESS";
        
        /** 重试耗尽 - 达到最大重试次数 */
        public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
        
        /** 人工处理 - 已由人工标记为已处理 */
        public static final String MANUALLY_RESOLVED = "MANUALLY_RESOLVED";
    }
    
    /**
     * 默认配置
     */
    public static class DefaultConfig {
        /** 默认最大重试次数 */
        public static final int DEFAULT_MAX_RETRY_COUNT = 3;
    }
}


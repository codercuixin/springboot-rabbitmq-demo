package com.example.rabbitmq.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * RabbitMQ 配置类
 * 统一定义队列、交换机、绑定关系等
 * 包括：主业务队列、死信队列、延迟重试队列
 */
@Configuration
public class RabbitMQConfig {

    // ========== 主业务配置 ==========
    
    // 队列名称
    public static final String QUEUE_NAME = "demo.queue";
    
    // 交换机名称
    public static final String EXCHANGE_NAME = "demo.exchange";
    
    // 路由键
    public static final String ROUTING_KEY = "demo.routing.key";

    // ========== 死信队列配置 ==========
    
    // 死信交换机
    public static final String DLX_EXCHANGE_NAME = "dlx.exchange";
    
    // 死信队列
    public static final String DLX_QUEUE_NAME = "dlx.queue";
    
    // 死信路由键
    public static final String DLX_ROUTING_KEY = "dlx.routing.key";
    
    // ========== 延迟重试配置 ==========
    
    // 延迟重试队列（用于延迟重试）
    public static final String RETRY_QUEUE_NAME = "demo.retry.queue";
    public static final String RETRY_EXCHANGE_NAME = "demo.retry.exchange";
    public static final String RETRY_ROUTING_KEY = "demo.retry.routing.key";
    
    // 重试延迟时间（毫秒）
    public static final int RETRY_DELAY_MS = 5000; // 5秒
    
    // ========== Alternate Exchange 配置（路由失败消息备用队列）==========
    
    // 备用交换机（当消息路由失败时，自动转发到这里）
    public static final String ALTERNATE_EXCHANGE_NAME = "alternate.exchange";
    
    // 未路由消息队列
    public static final String UNROUTED_QUEUE_NAME = "unrouted.messages.queue";

    // ========== 主业务队列和交换机 ==========

    /**
     * 主业务队列（配置了死信交换机）
     * 
     * 重要说明：
     * 1. 这是实际使用的主业务队列，配置了死信交换机
     * 2. 当消息被拒绝（basicNack/basicReject）且 requeue=false 时，会自动发送到死信队列
     * 3. 配置了 x-dead-letter-exchange 和 x-dead-letter-routing-key 参数
     */
    @Bean
    public Queue demoQueueWithDLX() {
        Map<String, Object> args = new HashMap<>();
        // 配置死信交换机
        args.put("x-dead-letter-exchange", DLX_EXCHANGE_NAME);
        // 配置死信路由键
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        
        return QueueBuilder.durable(QUEUE_NAME)
                .withArguments(args)
                .build();
    }

    /**
     * 声明主业务直连交换机
     * 配置 alternate-exchange：当消息路由失败时，自动转发到备用交换机
     */
    @Bean
    public DirectExchange demoExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_NAME)
                .durable(true)
                .alternate(ALTERNATE_EXCHANGE_NAME)  // 配置备用交换机
                .build();
    }

    /**
     * 绑定主业务队列到交换机
     */
    @Bean
    public Binding binding(Queue demoQueueWithDLX, DirectExchange demoExchange) {
        return BindingBuilder.bind(demoQueueWithDLX)
                .to(demoExchange)
                .with(ROUTING_KEY);
    }

    // ========== 死信队列和交换机 ==========

    /**
     * 死信交换机
     */
    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE_NAME)
                .durable(true)
                .build();
    }

    /**
     * 死信队列
     */
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE_NAME)
                .build();
    }

    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding dlxBinding(Queue dlxQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlxQueue)
                .to(dlxExchange)
                .with(DLX_ROUTING_KEY);
    }

    // ========== 延迟重试队列和交换机 ==========

    /**
     * 延迟重试交换机
     */
    @Bean
    public DirectExchange retryExchange() {
        return ExchangeBuilder.directExchange(RETRY_EXCHANGE_NAME)
                .durable(true)
                .build();
    }

    /**
     * 延迟重试队列，用于处理临时失败的消息
     * 消息在这个队列中等待一段时间后，会被自动转发到主业务队列
     */
    @Bean
    public Queue retryQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息过期时间（TTL）
        args.put("x-message-ttl", RETRY_DELAY_MS);
        // 过期后发送到主业务交换机
        args.put("x-dead-letter-exchange", EXCHANGE_NAME);
        // 使用主业务路由键
        args.put("x-dead-letter-routing-key", ROUTING_KEY);
        
        return QueueBuilder.durable(RETRY_QUEUE_NAME)
                .withArguments(args)
                .build();
    }

    /**
     * 绑定延迟重试队列
     */
    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue)
                .to(retryExchange)
                .with(RETRY_ROUTING_KEY);
    }

    // ========== Alternate Exchange 配置（路由失败处理）==========

    /**
     * 备用交换机（Fanout 类型，接收所有路由失败的消息）
     * 
     * 工作原理：
     * 1. 当主交换机无法路由消息时，消息会自动转发到这个备用交换机
     * 2. 使用 Fanout 类型，将失败消息广播到所有绑定的队列
     * 3. 这样可以统一收集和处理所有路由失败的消息
     */
    @Bean
    public FanoutExchange alternateExchange() {
        return new FanoutExchange(ALTERNATE_EXCHANGE_NAME, true, false);
    }

    /**
     * 未路由消息队列
     * 用于收集所有路由失败的消息
     */
    @Bean
    public Queue unroutedQueue() {
        return QueueBuilder.durable(UNROUTED_QUEUE_NAME)
                .build();
    }

    /**
     * 绑定未路由消息队列到备用交换机
     * Fanout 交换机不需要指定路由键，所有消息都会路由到绑定的队列
     */
    @Bean
    public Binding alternateBinding(Queue unroutedQueue, FanoutExchange alternateExchange) {
        return BindingBuilder.bind(unroutedQueue)
                .to(alternateExchange);
    }

    // ========== 其他配置 ==========

    /**
     * 配置消息转换器（JSON格式）
     * 注册 JavaTimeModule 以支持 Java 8 日期时间类型（如 LocalDateTime）
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 配置 RabbitTemplate
     * 用于发送消息，并配置 Publisher Confirm 回调
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        
        // 设置消息转换器
        rabbitTemplate.setMessageConverter(messageConverter);
        
        // 设置为 true，当消息无法路由到队列时，会触发 ReturnCallback
        rabbitTemplate.setMandatory(true);
        
        return rabbitTemplate;
    }
}


package com.example.rabbitmq.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 连接池和性能配置
 */
@Configuration
public class RabbitMQConnectionConfig {

    @Value("${spring.rabbitmq.listener.simple.prefetch:1}")
    private int prefetchCount;
    
    @Value("${spring.rabbitmq.listener.simple.concurrency:3}")
    private int concurrentConsumers;
    
    @Value("${spring.rabbitmq.listener.simple.max-concurrency:10}")
    private int maxConcurrentConsumers;

    /**
     * 配置连接工厂
     * 优化连接池设置
     */
    @Bean
    public CachingConnectionFactory connectionFactory(
            org.springframework.boot.autoconfigure.amqp.RabbitProperties properties) {
        
        CachingConnectionFactory factory = new CachingConnectionFactory();
        
        // 基本连接配置
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassword());
        factory.setVirtualHost(properties.getVirtualHost());
        
        // 连接池配置
        factory.setChannelCacheSize(25);  // 缓存的 Channel 数量
        factory.setChannelCheckoutTimeout(2000);  // Channel 获取超时时间（毫秒）
        
        // Publisher 配置
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        
        // 连接恢复配置
        factory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);
        factory.getRabbitConnectionFactory().setNetworkRecoveryInterval(5000);  // 5秒
        
        // 连接超时配置
        factory.getRabbitConnectionFactory().setConnectionTimeout(15000);  // 15秒
        factory.getRabbitConnectionFactory().setHandshakeTimeout(10000);  // 10秒
        
        return factory;
    }

    /**
     * 自定义监听器容器工厂
     * 用于更精细的控制消费者行为
     */
    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter) {
        
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        
        // 应用默认配置
        configurer.configure(factory, connectionFactory);
        
        // 自定义配置
        factory.setMessageConverter(messageConverter);
        factory.setPrefetchCount(prefetchCount);
        factory.setConcurrentConsumers(concurrentConsumers);
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);
        
        // 设置默认重新入队为 false（失败的消息不重新入队）
        factory.setDefaultRequeueRejected(false);
        
        return factory;
    }
}


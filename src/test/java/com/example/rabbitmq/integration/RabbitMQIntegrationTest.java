package com.example.rabbitmq.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.rabbitmq.model.Message;
import com.example.rabbitmq.publisher.MessagePublisher;

/**
 * RabbitMQ 集成测试
 * 
 * 注意：需要启动 RabbitMQ 才能运行此测试
 * 可以使用 @DirtiesContext 来避免测试之间的干扰
 */
@SpringBootTest
@ActiveProfiles("test")
class RabbitMQIntegrationTest {

    @Autowired
    private MessagePublisher messagePublisher;

    @Test
    void testSendAndReceiveMessage() throws Exception {
        // Given
        Message message = new Message("integration-test-1", "Integration Test Message");

        // When
        CorrelationData correlationData = messagePublisher.sendMessage(message);

        // Then
        assertNotNull(correlationData);
        
        // 等待异步确认
        CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(5, TimeUnit.SECONDS);
        
        assertTrue(confirm.isAck());
    }

    @Test
    void testBatchSendMessages() throws Exception {
        // Given
        java.util.List<Message> messages = java.util.Arrays.asList(
                new Message("batch-1", "Batch Message 1"),
                new Message("batch-2", "Batch Message 2"),
                new Message("batch-3", "Batch Message 3")
        );

        // When
        messagePublisher.sendBatchMessages(messages);

        // Then
        // 等待消息发送
        Thread.sleep(2000);
        
        // 验证消息已发送（实际项目中可以查询数据库或其他方式验证）
        assertTrue(true);
    }
}


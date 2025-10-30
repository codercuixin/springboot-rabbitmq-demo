package com.example.rabbitmq.publisher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.example.rabbitmq.config.RabbitMQConfig;
import com.example.rabbitmq.model.Message;

/**
 * MessagePublisher 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MessagePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private MessagePublisher messagePublisher;

    private Message testMessage;

    @BeforeEach
    void setUp() {
        testMessage = new Message("test-id", "Test Message");
    }

    @Test
    void testSendMessage() {
        // When
        CorrelationData result = messagePublisher.sendMessage(testMessage);

        // Then
        assertNotNull(result);
        assertNotNull(result.getId());
        
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY),
                eq(testMessage),
                any(CorrelationData.class)
        );
    }

    @Test
    void testSendMessageToWrongRoutingKey() {
        // When
        messagePublisher.sendMessageToWrongRoutingKey(testMessage);

        // Then
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq("wrong.routing.key"),
                eq(testMessage),
                any(CorrelationData.class)
        );
    }
}


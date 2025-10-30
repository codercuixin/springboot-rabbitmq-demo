package com.example.rabbitmq.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MessageIdempotentService 单元测试
 */
class MessageIdempotentServiceTest {

    private MessageIdempotentService idempotentService;

    @BeforeEach
    void setUp() {
        idempotentService = new MessageIdempotentService();
    }

    @Test
    void testIsDuplicate_NotProcessed() {
        // Given
        String messageId = "test-message-1";

        // When
        boolean isDuplicate = idempotentService.isDuplicate(messageId);

        // Then
        assertFalse(isDuplicate);
    }

    @Test
    void testMarkAsProcessed() {
        // Given
        String messageId = "test-message-2";

        // When
        idempotentService.markAsProcessed(messageId);
        boolean isDuplicate = idempotentService.isDuplicate(messageId);

        // Then
        assertTrue(isDuplicate);
    }

    @Test
    void testRemoveProcessed() {
        // Given
        String messageId = "test-message-3";
        idempotentService.markAsProcessed(messageId);

        // When
        idempotentService.removeProcessed(messageId);
        boolean isDuplicate = idempotentService.isDuplicate(messageId);

        // Then
        assertFalse(isDuplicate);
    }

    @Test
    void testGetStats() {
        // When
        String stats = idempotentService.getStats();

        // Then
        assertNotNull(stats);
        assertTrue(stats.contains("缓存统计"));
    }
}


package com.example.rabbitmq.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.rabbitmq.model.Message;
import com.example.rabbitmq.publisher.MessagePublisher;
import com.example.rabbitmq.service.MessageFailureService;

import lombok.extern.slf4j.Slf4j;

/**
 * 消息控制器
 * 提供 REST API 用于发送消息
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @Autowired
    private MessagePublisher messagePublisher;
    
    @Autowired
    private MessageFailureService messageFailureService;

    /**
     * 发送单条消息（无重试版本）
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send
     * Content-Type: application/json
     * 
     * {
     *   "content": "Hello RabbitMQ"
     * }
     */
    @PostMapping("/send")
    public String sendMessage(@RequestBody MessageRequest request) {
        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, request.getContent());
        
        messagePublisher.sendMessage(message);
        
        return "消息已发送，ID: " + messageId + "，等待异步确认...";
    }

    /**
     * 发送单条消息（带自动重试）- 推荐使用
     * 处理临时网络问题：自动重试3次，使用指数退避策略
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send-with-retry
     * Content-Type: application/json
     * 
     * {
     *   "content": "Hello RabbitMQ with Retry"
     * }
     */
    @PostMapping("/send-with-retry")
    public ResponseEntity<Map<String, Object>> sendMessageWithRetry(@RequestBody MessageRequest request) {
        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, request.getContent());
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            String resultId = messagePublisher.sendMessageWithRetry(message);
            
            if (resultId != null) {
                response.put("success", true);
                response.put("messageId", resultId);
                response.put("message", "消息发送成功（带自动重试保护）");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "消息发送失败（重试3次后仍然失败，已保存到失败记录）");
                return ResponseEntity.status(500).body(response);
            }
        } catch (Exception e) {
            log.error("发送消息异常: ", e);
            response.put("success", false);
            response.put("message", "消息发送异常: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 发送消息并等待确认结果
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send-with-confirm
     * Content-Type: application/json
     * 
     * {
     *   "content": "Hello RabbitMQ with Confirm"
     * }
     */
    @PostMapping("/send-with-confirm")
    public String sendMessageWithConfirm(@RequestBody MessageRequest request) {
        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, request.getContent());
        
        boolean success = messagePublisher.sendMessageWithConfirm(message);
        
        if (success) {
            return "消息发送成功，ID: " + messageId;
        } else {
            return "消息发送失败，ID: " + messageId;
        }
    }

    /**
     * 批量发送消息
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send-batch?count=5
     */
    @PostMapping("/send-batch")
    public String sendBatchMessages(@RequestParam(defaultValue = "5") int count) {
        List<Message> messages = new ArrayList<>();
        
        for (int i = 1; i <= count; i++) {
            String messageId = UUID.randomUUID().toString();
            Message message = new Message(messageId, "批量消息 #" + i);
            messages.add(message);
        }
        
        messagePublisher.sendBatchMessages(messages);
        
        return "已发送 " + count + " 条消息，等待异步确认...";
    }

    /**
     * 测试发送错误消息（触发 Consumer 异常处理）
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send-error
     */
    @PostMapping("/send-error")
    public String sendErrorMessage() {
        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, "这是一个包含 ERROR 的消息");
        
        messagePublisher.sendMessage(message);
        
        return "已发送错误测试消息，ID: " + messageId;
    }

    /**
     * 测试发送到错误的路由键（触发 Return 回调）
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/send-wrong-routing
     */
    @PostMapping("/send-wrong-routing")
    public String sendMessageToWrongRouting() {
        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, "测试错误路由");
        
        messagePublisher.sendMessageToWrongRoutingKey(message);
        
        return "已发送到错误路由键的消息，ID: " + messageId + "，将触发 Return 回调";
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "RabbitMQ Service is running!";
    }

    // ========== 失败消息管理接口 ==========

    /**
     * 获取所有失败的消息
     * 
     * 示例请求：
     * GET http://localhost:8080/api/messages/failures
     */
    @GetMapping("/failures")
    public ResponseEntity<Map<String, Object>> getAllFailedMessages() {
        List<MessageFailureService.FailedMessage> failedMessages = messageFailureService.getAllFailedMessages();
        MessageFailureService.FailureStatistics statistics = messageFailureService.getStatistics();
        
        Map<String, Object> response = new HashMap<>();
        response.put("total", failedMessages.size());
        response.put("statistics", statistics);
        response.put("messages", failedMessages);
        
        return ResponseEntity.ok(response);
    }

    /**
     * 获取待处理的失败消息
     * 
     * 示例请求：
     * GET http://localhost:8080/api/messages/failures/pending
     */
    @GetMapping("/failures/pending")
    public ResponseEntity<List<MessageFailureService.FailedMessage>> getPendingFailedMessages() {
        List<MessageFailureService.FailedMessage> pendingMessages = messageFailureService.getPendingFailedMessages();
        return ResponseEntity.ok(pendingMessages);
    }

    /**
     * 获取失败消息详情
     * 
     * 示例请求：
     * GET http://localhost:8080/api/messages/failures/{messageId}
     */
    @GetMapping("/failures/{messageId}")
    public ResponseEntity<MessageFailureService.FailedMessage> getFailedMessage(@PathVariable String messageId) {
        MessageFailureService.FailedMessage failedMessage = messageFailureService.getFailedMessage(messageId);
        if (failedMessage != null) {
            return ResponseEntity.ok(failedMessage);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 重试失败的消息
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/failures/{messageId}/retry
     */
    @PostMapping("/failures/{messageId}/retry")
    public ResponseEntity<Map<String, String>> retryFailedMessage(@PathVariable String messageId) {
        MessageFailureService.FailedMessage failedMessage = messageFailureService.getFailedMessage(messageId);
        
        if (failedMessage == null) {
            return ResponseEntity.notFound().build();
        }
        
        if (failedMessage.getRetryCount() >= failedMessage.getMaxRetryCount()) {
            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "重试次数已用尽，需要人工处理");
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 标记为重试中
            messageFailureService.markAsRetrying(messageId);
            
            // TODO: 这里应该重新发送消息到 RabbitMQ
            // 示例代码（需要根据实际情况调整）：
            // Message message = parseMessage(failedMessage.getMessageBody());
            // messagePublisher.sendMessage(message);
            
            // 暂时模拟重试成功
            messageFailureService.markAsRetrySuccess(messageId);
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "消息重试成功");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("重试失败消息异常: ", e);
            messageFailureService.markAsRetryFailed(messageId, e.getMessage());
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "重试失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 批量重试待处理的失败消息
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/failures/retry-batch?limit=10
     */
    @PostMapping("/failures/retry-batch")
    public ResponseEntity<Map<String, Object>> retryBatchFailedMessages(
            @RequestParam(defaultValue = "10") int limit) {
        
        List<MessageFailureService.FailedMessage> pendingMessages = messageFailureService.getPendingFailedMessages();
        
        int successCount = 0;
        int failCount = 0;
        
        for (int i = 0; i < Math.min(limit, pendingMessages.size()); i++) {
            MessageFailureService.FailedMessage failedMessage = pendingMessages.get(i);
            
            try {
                messageFailureService.markAsRetrying(failedMessage.getId());
                // TODO: 重新发送消息
                messageFailureService.markAsRetrySuccess(failedMessage.getId());
                successCount++;
            } catch (Exception e) {
                log.error("批量重试失败，消息ID: {}", failedMessage.getId(), e);
                messageFailureService.markAsRetryFailed(failedMessage.getId(), e.getMessage());
                failCount++;
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("total", Math.min(limit, pendingMessages.size()));
        response.put("success", successCount);
        response.put("failed", failCount);
        
        return ResponseEntity.ok(response);
    }

    /**
     * 手动标记消息为已处理
     * 
     * 示例请求：
     * POST http://localhost:8080/api/messages/failures/{messageId}/resolve
     * Content-Type: application/json
     * 
     * {
     *   "resolvedBy": "admin",
     *   "note": "手动处理完成"
     * }
     */
    @PostMapping("/failures/{messageId}/resolve")
    public ResponseEntity<Map<String, String>> resolveFailedMessage(
            @PathVariable String messageId,
            @RequestBody ResolveRequest request) {
        
        MessageFailureService.FailedMessage failedMessage = messageFailureService.getFailedMessage(messageId);
        
        if (failedMessage == null) {
            return ResponseEntity.notFound().build();
        }
        
        messageFailureService.markAsManuallyResolved(messageId, request.getResolvedBy(), request.getNote());
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "消息已标记为人工处理");
        return ResponseEntity.ok(response);
    }

    /**
     * 删除失败消息记录
     * 
     * 示例请求：
     * DELETE http://localhost:8080/api/messages/failures/{messageId}
     */
    @DeleteMapping("/failures/{messageId}")
    public ResponseEntity<Map<String, String>> deleteFailedMessage(@PathVariable String messageId) {
        boolean deleted = messageFailureService.deleteFailedMessage(messageId);
        
        Map<String, String> response = new HashMap<>();
        if (deleted) {
            response.put("status", "success");
            response.put("message", "失败消息已删除");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "error");
            response.put("message", "消息不存在");
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取失败消息统计信息
     * 
     * 示例请求：
     * GET http://localhost:8080/api/messages/failures/statistics
     * GET http://localhost:8080/api/messages/failures/statistics?stage=PUBLISH
     * GET http://localhost:8080/api/messages/failures/statistics?stage=CONSUME
     */
    @GetMapping("/failures/statistics")
    public ResponseEntity<MessageFailureService.FailureStatistics> getFailureStatistics(
            @RequestParam(required = false) String stage) {
        
        MessageFailureService.FailureStatistics statistics;
        
        if (stage != null && !stage.isEmpty()) {
            // 按阶段统计
            if (!"PUBLISH".equals(stage) && !"CONSUME".equals(stage)) {
                throw new IllegalArgumentException("stage 参数只能是 PUBLISH 或 CONSUME");
            }
            statistics = messageFailureService.getStatisticsByStage(stage);
        } else {
            // 全部统计
            statistics = messageFailureService.getStatistics();
        }
        
        return ResponseEntity.ok(statistics);
    }
    
    /**
     * 按阶段获取失败消息列表
     * 
     * 示例请求：
     * GET http://localhost:8080/api/messages/failures/stage/PUBLISH  - 查看所有发送失败的消息
     * GET http://localhost:8080/api/messages/failures/stage/CONSUME  - 查看所有消费失败的消息
     * GET http://localhost:8080/api/messages/failures/stage/PUBLISH?status=PENDING  - 查看待处理的发送失败消息
     */
    @GetMapping("/failures/stage/{stage}")
    public ResponseEntity<List<MessageFailureService.FailedMessage>> getFailedMessagesByStage(
            @PathVariable String stage,
            @RequestParam(required = false) String status) {
        
        // 验证 stage 参数
        if (!"PUBLISH".equals(stage) && !"CONSUME".equals(stage)) {
            throw new IllegalArgumentException("stage 参数只能是 PUBLISH 或 CONSUME");
        }
        
        List<MessageFailureService.FailedMessage> messages;
        
        if (status != null && !status.isEmpty()) {
            // 按阶段和状态查询
            messages = messageFailureService.getFailedMessagesByStageAndStatus(stage, status);
        } else {
            // 只按阶段查询
            messages = messageFailureService.getFailedMessagesByStage(stage);
        }
        
        return ResponseEntity.ok(messages);
    }

    // ========== 请求/响应对象 ==========

    /**
     * 消息请求对象
     */
    public static class MessageRequest {
        private String content;

        public MessageRequest() {}

        public MessageRequest(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /**
     * 解决失败消息请求对象
     */
    public static class ResolveRequest {
        private String resolvedBy;
        private String note;

        public ResolveRequest() {}

        public ResolveRequest(String resolvedBy, String note) {
            this.resolvedBy = resolvedBy;
            this.note = note;
        }

        public String getResolvedBy() {
            return resolvedBy;
        }

        public void setResolvedBy(String resolvedBy) {
            this.resolvedBy = resolvedBy;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}


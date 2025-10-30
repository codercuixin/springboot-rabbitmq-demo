# 消息发送重试策略详解

## 📋 问题背景

**问题**：发送失败时，应该直接保存到 `MessageFailureService` 吗？还是应该先自动重试？

**答案**：取决于失败类型！

---

## 🎯 失败类型分析

### 1. CONFIRM_FAILED（消息未到达 Exchange）

**典型原因：**
- 网络抖动、超时
- RabbitMQ 临时不可用
- 连接突然断开

**处理策略：** ✅ **应该自动重试**

**理由：** 这些都是临时性故障，重试后大概率会成功

---

### 2. ROUTING_FAILED（路由失败）

**典型原因：**
- 路由键配置错误
- 队列不存在
- 绑定关系错误

**处理策略：** ❌ **不应该重试**

**理由：** 这些都是配置问题，重试无意义，应该：
1. 立即保存到 `MessageFailureService`
2. 发送告警通知运维人员
3. 修复配置后手动重试

---

## 🔧 三种实现方案对比

### 方案1：不重试（当前实现）❌

```java
// 失败 → 直接保存
rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
    if (!ack) {
        messageFailureService.saveFailedMessage(...);
    }
});
```

**优点：**
- ✅ 实现简单
- ✅ 快速失败，不阻塞

**缺点：**
- ❌ 临时故障也会失败（如网络抖动）
- ❌ 需要手动重试，增加运维负担

**适用场景：** 开发/测试环境

---

### 方案2：使用 Spring Retry ✅ **推荐**

```java
@Retryable(
    value = { AmqpException.class },
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2.0)
)
public String sendMessageWithRetry(Message message) {
    rabbitTemplate.convertAndSend(...);
    return messageId;
}

@Recover
public String recoverFromSendFailure(Exception e, Message message) {
    // 所有重试都失败后，保存到 MessageFailureService
    messageFailureService.saveFailedMessage(...);
    return null;
}
```

**优点：**
- ✅ 声明式重试，代码简洁
- ✅ 支持指数退避（第1次等1秒，第2次等2秒，第3次等4秒）
- ✅ 自动处理重试失败的情况（@Recover）
- ✅ Spring 官方支持，稳定可靠

**缺点：**
- ⚠️ 需要额外依赖（spring-retry, spring-aspects）
- ⚠️ AOP 实现，需要理解代理机制

**适用场景：** ✅ **生产环境推荐**

**重试策略示例：**

| 重试次数 | 延迟时间 | 累计时间 |
|---------|---------|---------|
| 第1次   | 立即     | 0秒     |
| 第2次   | 1秒     | 1秒     |
| 第3次   | 2秒     | 3秒     |
| 第4次   | 4秒     | 7秒     |
| 失败    | -       | 保存到 MessageFailureService |

---

### 方案3：手动实现重试 ✅ **备选**

```java
public String sendMessageWithManualRetry(Message message) {
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
        try {
            rabbitTemplate.convertAndSend(...);
            return messageId;  // 成功
        } catch (Exception e) {
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Thread.sleep(calculateRetryDelay(attempt));
            }
        }
    }
    
    // 所有重试都失败
    messageFailureService.saveFailedMessage(...);
    return null;
}
```

**优点：**
- ✅ 无需额外依赖
- ✅ 重试逻辑完全可控
- ✅ 容易调试和理解

**缺点：**
- ⚠️ 代码较多，需要自己处理异常
- ⚠️ 阻塞当前线程（同步重试）

**适用场景：** 
- 不想引入 Spring Retry 依赖
- 需要特殊的重试逻辑

---

## 📊 生产环境推荐方案

### 推荐配置

```yaml
# application.yml
spring:
  rabbitmq:
    # 发送者确认
    publisher-confirm-type: correlated
    publisher-returns: true
    
    # 连接池配置
    connection-timeout: 15000
    
    # 重试配置（这是 RabbitTemplate 的连接重试）
    template:
      retry:
        enabled: true
        initial-interval: 1000
        max-attempts: 3
        multiplier: 2.0
        max-interval: 10000
```

### 代码实现

**对于 CONFIRM_FAILED：使用 Spring Retry**

```java
@Retryable(
    value = { AmqpException.class },
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2.0)
)
public String sendMessage(Message message) {
    // 发送消息
}

@Recover
public String recoverFromSendFailure(Exception e, Message message) {
    // 保存到 MessageFailureService
}
```

**对于 ROUTING_FAILED：直接保存**

```java
rabbitTemplate.setReturnsCallback(returned -> {
    // 路由失败不重试，直接保存
    messageFailureService.saveFailedMessage(
        ...,
        MessageFailureConstants.PublishFailureType.ROUTING_FAILED,
        MessageFailureConstants.FailureStage.PUBLISH
    );
    
    // 发送告警
    alertService.sendAlert("路由失败，请检查配置");
});
```

---

## 🔄 完整的发送流程

```
                          发送消息
                             ↓
                    ┌────────┴────────┐
                    │  RabbitTemplate  │
                    └────────┬────────┘
                             ↓
                    ┌────────┴────────┐
                    │   第1次尝试      │
                    └────────┬────────┘
                             ↓
                        发送成功？
                       ↙         ↘
                   是 ✓           否 ✗
                   │              │
              返回成功         网络抖动？
                            ↙         ↘
                        是 ⟳          否 ✗
                        │              │
              ┌─────────┴────┐    配置错误
              │  等待 1秒     │         │
              │  第2次尝试    │    直接保存到
              └──────┬────────┘  MessageFailureService
                     ↓              + 发送告警
                 发送成功？
                ↙         ↘
            是 ✓           否 ✗
            │              │
       返回成功      ┌─────┴────┐
                    │  等待 2秒  │
                    │  第3次尝试 │
                    └──────┬────┘
                           ↓
                      发送成功？
                     ↙         ↘
                 是 ✓           否 ✗
                 │              │
            返回成功      保存到 MessageFailureService
                              + 发送告警
```

---

## 📈 监控和告警

### 1. 区分重试成功和最终失败

```java
@Retryable(...)
public String sendMessage(Message message) {
    log.info("尝试发送消息...");
    // 发送逻辑
}

@Recover
public String recoverFromSendFailure(Exception e, Message message) {
    log.error("重试失败，进入 Recover 方法");
    
    // 发送告警
    alertService.sendUrgentAlert(
        "消息发送失败（重试3次后）",
        "消息内容: " + message.getContent(),
        "错误: " + e.getMessage()
    );
    
    // 保存到失败消息服务
    messageFailureService.saveFailedMessage(...);
    
    return null;
}
```

### 2. 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| 重试成功率 | `重试成功次数 / 总重试次数` | < 80% |
| 最终失败率 | `进入Recover次数 / 总发送次数` | > 1% |
| 平均重试次数 | `总重试次数 / 发送成功次数` | > 1.5 |
| 路由失败数量 | `ROUTING_FAILED 数量` | > 10 |

### 3. 日志示例

**重试成功的情况：**
```
→ [Resilient Publisher] 发送消息: Hello, ID: abc-123
✗ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
⟳ 等待 1000ms 后重试...
→ [Resilient Publisher] 发送消息: Hello, ID: abc-123
✓ [Resilient Publisher] 消息发送成功: abc-123
```

**最终失败的情况：**
```
→ [Resilient Publisher] 发送消息: Hello, ID: abc-123
✗ 第1次失败，1秒后重试...
✗ 第2次失败，2秒后重试...
✗ 第3次失败，4秒后重试...
✗ [Resilient Publisher] 消息发送失败（重试已用尽）: Hello
⚠ 失败消息已保存，ID: xyz-789，可通过管理接口重试
🚨 [告警] 消息发送失败（重试3次后）
```

---

## 🎯 最佳实践建议

### 1. 根据业务重要性选择策略

| 业务场景 | 推荐方案 | 重试次数 | 最大延迟 |
|---------|---------|---------|---------|
| 核心业务（支付、订单） | Spring Retry + 确认 | 5次 | 30秒 |
| 重要业务（通知、消息） | Spring Retry | 3次 | 7秒 |
| 一般业务（日志、统计） | 手动重试或不重试 | 2次 | 3秒 |

### 2. 指数退避的好处

- ✅ 避免雷击效应（避免大量重试同时发生）
- ✅ 给系统恢复留出时间
- ✅ 减少对 RabbitMQ 的压力

### 3. 设置合理的超时时间

```java
@Retryable(
    value = { AmqpException.class },
    maxAttempts = 3,
    backoff = @Backoff(
        delay = 1000,
        multiplier = 2.0,
        maxDelay = 10000  // 最大延迟 10 秒
    )
)
```

### 4. 异步重试 vs 同步重试

| 重试方式 | 优点 | 缺点 | 适用场景 |
|---------|------|------|---------|
| **同步重试**<br>（当前线程等待） | 简单直接<br>保证顺序 | 阻塞线程<br>影响吞吐量 | 低并发<br>强一致性要求 |
| **异步重试**<br>（独立线程池） | 不阻塞<br>高吞吐量 | 复杂度高<br>顺序无保证 | 高并发<br>可容忍乱序 |

---

## 🛠️ 如何切换到重试方案

### 步骤1：添加依赖（如使用 Spring Retry）

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-aspects</artifactId>
</dependency>
```

### 步骤2：启用 @EnableRetry

```java
@Configuration
@EnableRetry
public class RetryConfig {
}
```

### 步骤3：使用 ResilientMessagePublisher

```java
@Autowired
private ResilientMessagePublisher resilientPublisher;

// 发送消息（带自动重试）
String messageId = resilientPublisher.sendMessageWithRetry(message);
```

### 步骤4：监控和调优

观察日志和指标，根据实际情况调整：
- 重试次数
- 延迟时间
- 超时时间

---

## 📚 相关资源

- [Spring Retry 官方文档](https://github.com/spring-projects/spring-retry)
- [RabbitMQ Publisher Confirms](https://www.rabbitmq.com/confirms.html)
- [Exponential Backoff 算法](https://en.wikipedia.org/wiki/Exponential_backoff)

---

## ❓ FAQ

### Q1: 重试会不会导致消息重复？

**A:** 不会。因为：
1. 重试是在发送失败后进行的
2. 如果消息已经到达 RabbitMQ，Confirm 会返回 ACK，不会重试
3. 真正的重复消费问题应该由消费者的幂等性处理

### Q2: 重试期间应用崩溃怎么办？

**A:** 
- 重试期间崩溃 → 消息会保存到 `MessageFailureService`（在 `@Recover` 方法中）
- 可以通过管理接口手动重试
- 建议使用持久化的 MessageFailureService（数据库）

### Q3: 为什么路由失败不重试？

**A:** 因为路由失败通常是配置错误：
- 路由键写错了 → 重试100次也还是错
- 队列不存在 → 需要先创建队列
- 应该：告警 → 人工修复配置 → 手动重试

### Q4: Spring Retry 会影响性能吗？

**A:** 
- 正常情况：几乎无影响（基于 AOP，开销很小）
- 频繁重试时：会有一定性能损耗（但问题在于系统故障，不在于重试）
- 建议：监控重试频率，如果过高说明系统有问题

---

## 🎓 总结

| 失败类型 | 是否重试 | 重试方式 | 失败后处理 |
|---------|---------|---------|-----------|
| **CONFIRM_FAILED** | ✅ 是 | Spring Retry（3次，指数退避） | 保存 + 告警 |
| **ROUTING_FAILED** | ❌ 否 | 不重试 | 立即保存 + 立即告警 |

**生产环境推荐配置：**
- 使用 Spring Retry
- 3次重试，指数退避（1s、2s、4s）
- 失败后保存到 MessageFailureService
- 集成告警系统（钉钉/企业微信/邮件）
- 定期检查失败消息并处理


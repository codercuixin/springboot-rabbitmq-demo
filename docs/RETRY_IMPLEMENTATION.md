# 消息发送重试机制实现说明

## 📋 问题背景

**原始问题：** 当消息发送时遇到临时网络问题（如网络抖动、连接超时），系统会直接将消息标记为失败并保存到 `MessageFailureService`，需要手动处理。

**改进目标：** 自动处理临时网络问题，只有在多次重试失败后才保存为失败消息。

---

## ✨ 解决方案

使用 **Spring Retry** 框架实现自动重试机制，采用指数退避策略：

| 尝试次数 | 延迟时间 | 累计时间 | 说明 |
|---------|---------|---------|------|
| 第 1 次 | 立即    | 0秒     | 初始尝试 |
| 第 2 次 | 1秒     | 1秒     | 第1次重试 |
| 第 3 次 | 2秒     | 3秒     | 第2次重试 |
| 第 4 次 | 4秒     | 7秒     | 第3次重试 |
| 失败    | -       | -       | 保存到 MessageFailureService + 告警 |

---

## 🔧 实现细节

### 1. 添加依赖 (pom.xml)

```xml
<!-- Spring Retry (for automatic retry on temporary failures) -->
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-aspects</artifactId>
</dependency>
```

### 2. 启用 Spring Retry

创建配置类 `RetryConfig.java`：

```java
@Configuration
@EnableRetry
public class RetryConfig {
    // Spring Retry 会自动配置重试机制
}
```

### 3. 在 MessagePublisher 中添加重试方法

```java
@Retryable(
    retryFor = { AmqpException.class, Exception.class },
    maxAttempts = 4,  // 1次初始尝试 + 3次重试
    backoff = @Backoff(
        delay = 1000,        // 初始延迟 1 秒
        multiplier = 2.0,    // 每次延迟翻倍
        maxDelay = 10000     // 最大延迟 10 秒
    )
)
public String sendMessageWithRetry(Message message) {
    // 发送消息逻辑
}

@Recover
public String recoverFromSendFailure(Exception e, Message message) {
    // 所有重试失败后，保存到 MessageFailureService
    // 并发送告警
}
```

### 4. 新增 REST API 接口

在 `MessageController` 中添加新接口：

```java
@PostMapping("/send-with-retry")
public ResponseEntity<Map<String, Object>> sendMessageWithRetry(@RequestBody MessageRequest request) {
    // 调用带重试的发送方法
}
```

---

## 🎯 使用方式

### 方式 1：使用新的 API 接口（推荐）

```bash
curl -X POST http://localhost:8080/api/messages/send-with-retry \
  -H "Content-Type: application/json" \
  -d '{"content": "测试带重试的消息"}'
```

**成功响应：**
```json
{
  "success": true,
  "messageId": "abc-123-xyz",
  "message": "消息发送成功（带自动重试保护）"
}
```

**失败响应：**
```json
{
  "success": false,
  "message": "消息发送失败（重试3次后仍然失败，已保存到失败记录）"
}
```

### 方式 2：在代码中直接调用

```java
@Autowired
private MessagePublisher messagePublisher;

// 使用带重试的方法（推荐）
String messageId = messagePublisher.sendMessageWithRetry(message);

if (messageId != null) {
    // 发送成功
} else {
    // 发送失败（已重试3次）
}
```

---

## 📊 重试日志示例

### 成功场景（第2次重试成功）

```
→ [Resilient Publisher] 发送消息（带重试）: 测试消息, ID: abc-123
⟳ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
  [等待 1 秒后重试...]
→ [Resilient Publisher] 发送消息（带重试）: 测试消息, ID: abc-123
✓ [Resilient Publisher] 消息发送成功: abc-123
```

### 失败场景（重试3次后仍然失败）

```
→ [Resilient Publisher] 发送消息（带重试）: 测试消息, ID: abc-123
⟳ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
  [等待 1 秒后重试...]
⟳ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
  [等待 2 秒后重试...]
⟳ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
  [等待 4 秒后重试...]
⟳ [Resilient Publisher] 发送消息失败（将重试）: abc-123, 原因: Connection refused
✗ [Resilient Publisher] 消息发送失败（重试已用尽）: 测试消息, 错误: Connection refused
⚠ 失败消息已保存，ID: xyz-789，可通过管理接口重试
```

---

## 🔄 与原有方法的对比

| 特性 | sendMessage() | sendMessageWithRetry() ✅ |
|------|--------------|--------------------------|
| 自动重试 | ❌ 否 | ✅ 是（3次） |
| 指数退避 | ❌ 否 | ✅ 是（1s、2s、4s） |
| 临时网络问题 | ❌ 直接失败 | ✅ 自动处理 |
| 失败后保存 | ⚠️ 需要手动 | ✅ 自动保存 |
| 推荐场景 | 测试/开发 | **生产环境** |

---

## 🎭 测试场景

### 测试 1：正常发送（网络正常）

```bash
# 启动服务
docker-compose up -d
mvn spring-boot:run

# 发送消息
curl -X POST http://localhost:8080/api/messages/send-with-retry \
  -H "Content-Type: application/json" \
  -d '{"content": "正常消息"}'
```

**预期结果：** 第1次尝试成功

---

### 测试 2：模拟临时网络问题

```bash
# 1. 停止 RabbitMQ（模拟网络故障）
docker-compose stop rabbitmq

# 2. 发送消息（会自动重试）
curl -X POST http://localhost:8080/api/messages/send-with-retry \
  -H "Content-Type: application/json" \
  -d '{"content": "临时网络问题测试"}' &

# 3. 等待 2 秒后启动 RabbitMQ（模拟网络恢复）
sleep 2
docker-compose start rabbitmq
```

**预期结果：** 
- 第1次失败
- 等待1秒，第2次失败  
- 等待2秒，网络恢复
- 第3次成功 ✅

---

### 测试 3：模拟持续网络故障

```bash
# 1. 停止 RabbitMQ
docker-compose stop rabbitmq

# 2. 发送消息
curl -X POST http://localhost:8080/api/messages/send-with-retry \
  -H "Content-Type: application/json" \
  -d '{"content": "持续网络故障测试"}'

# 3. 保持 RabbitMQ 关闭状态

# 4. 查看失败消息
curl http://localhost:8080/api/messages/failed
```

**预期结果：** 
- 重试3次后失败
- 消息自动保存到失败记录
- 可以手动重试

---

## 📈 监控指标

建议监控以下指标：

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| 重试成功率 | 重试成功次数 / 总重试次数 | < 80% |
| 最终失败率 | 进入Recover次数 / 总发送次数 | > 1% |
| 平均重试次数 | 总重试次数 / 发送成功次数 | > 1.5 |
| 重试延迟 | 平均重试等待时间 | > 5秒 |

---

## 🔐 最佳实践

### 1. 根据业务重要性选择方法

| 业务场景 | 推荐方法 | 重试次数 | 最大延迟 |
|---------|---------|---------|---------|
| **核心业务**（支付、订单） | `sendMessageWithRetry()` | 5次 | 30秒 |
| **重要业务**（通知、消息） | `sendMessageWithRetry()` | 3次 | 7秒 |
| **一般业务**（日志、统计） | `sendMessage()` | 0次 | 0秒 |

### 2. 合理配置重试参数

```java
@Retryable(
    retryFor = { AmqpException.class },  // 只重试 AMQP 异常
    maxAttempts = 4,                      // 1次初始 + 3次重试
    backoff = @Backoff(
        delay = 1000,                     // 初始延迟 1秒
        multiplier = 2.0,                 // 指数退避
        maxDelay = 10000                  // 最大延迟 10秒
    )
)
```

### 3. 区分不同的失败类型

| 失败类型 | 是否重试 | 原因 |
|---------|---------|------|
| **CONFIRM_FAILED** | ✅ 是 | 可能是临时网络问题 |
| **ROUTING_FAILED** | ❌ 否 | 配置错误，重试无意义 |
| **SEND_EXCEPTION** | ✅ 是 | 可能是连接问题 |

### 4. 集成告警系统

```java
@Recover
public String recoverFromSendFailure(Exception e, Message message) {
    // 保存失败消息
    messageFailureService.saveFailedMessage(...);
    
    // 发送告警
    alertService.sendUrgentAlert(
        "消息发送失败（重试3次后）",
        "消息: " + message.getContent(),
        "错误: " + e.getMessage()
    );
    
    return null;
}
```

---

## 🚀 生产环境配置建议

### application.yml

```yaml
spring:
  rabbitmq:
    # 发送者确认
    publisher-confirm-type: correlated
    publisher-returns: true
    
    # 连接超时
    connection-timeout: 15000
    
    # RabbitMQ 连接层的重试（额外的保护）
    template:
      retry:
        enabled: true
        initial-interval: 1000
        max-attempts: 3
        multiplier: 2.0
        max-interval: 10000
```

**注意：** 这是 RabbitMQ 连接层的重试，与我们实现的业务层重试是两个不同的层次。

---

## 📚 相关文档

- [Spring Retry 官方文档](https://github.com/spring-projects/spring-retry)
- [RabbitMQ Publisher Confirms](https://www.rabbitmq.com/confirms.html)
- [Exponential Backoff 算法](https://en.wikipedia.org/wiki/Exponential_backoff)
- [消息发送重试策略详解](PUBLISH_RETRY_STRATEGY.md)

---

## ❓ FAQ

### Q1: 重试会导致消息重复吗？

**A:** 不会。因为：
1. 重试是在发送失败后进行的
2. 如果消息已经到达 RabbitMQ，Confirm 会返回 ACK，不会重试
3. 真正的重复消费问题应该由消费者的幂等性处理

### Q2: 重试期间应用崩溃怎么办？

**A:** 重试是同步的，如果应用崩溃：
- 当前正在重试的消息会丢失
- 建议使用消息持久化或事务方式处理关键消息
- 可以考虑实现异步重试队列

### Q3: 为什么路由失败不重试？

**A:** 路由失败通常是配置错误：
- 路由键写错了 → 重试100次也还是错
- 队列不存在 → 需要先创建队列
- 应该：告警 → 人工修复配置 → 手动重试

### Q4: 如何调整重试参数？

**A:** 根据业务需求调整 `@Retryable` 注解的参数：
- `maxAttempts`: 总尝试次数（包括首次）
- `delay`: 初始延迟（毫秒）
- `multiplier`: 延迟倍数（指数退避）
- `maxDelay`: 最大延迟（毫秒）

---

## 🎓 总结

### 主要改进

1. ✅ 添加了 Spring Retry 依赖
2. ✅ 创建了 RetryConfig 配置类
3. ✅ 实现了 `sendMessageWithRetry()` 方法
4. ✅ 实现了 `recoverFromSendFailure()` 恢复方法
5. ✅ 添加了新的 API 接口 `/send-with-retry`
6. ✅ 更新了文档和测试脚本

### 使用建议

- 🔥 **生产环境推荐使用** `sendMessageWithRetry()` 方法
- 📊 **监控重试指标**，及时发现系统问题
- 🚨 **集成告警系统**，第一时间响应失败
- 🔧 **定期处理失败消息**，保证业务完整性

---

**实施日期：** 2025-10-30  
**版本：** v1.1.0


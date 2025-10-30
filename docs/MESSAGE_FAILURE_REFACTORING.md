# 消息失败处理机制重构说明

## 📋 重构概述

**重构时间：** 2025-10-30  
**重构原因：** 统一管理发送失败和消费失败，避免职责不清晰

### 重构前的问题

1. **职责不清晰**：`MessageFailureService` 名字听起来应该处理所有失败，但实际只处理发送失败
2. **功能分散**：消费失败（死信队列）没有使用统一的失败处理机制
3. **难以监控**：无法统一查看系统的所有消息失败情况

### 重构后的改进

1. ✅ **统一管理**：一个服务处理所有阶段的失败（发送失败 + 消费失败）
2. ✅ **职责清晰**：通过 `failureStage` 字段区分失败阶段
3. ✅ **完整链路**：可以看到消息从发送到消费的完整失败链路
4. ✅ **统一监控**：一套 API 接口，统一查看和管理所有失败

---

## 🔄 核心变更

### 1. MessageFailureService 扩展

#### 新增字段：failureStage

```java
public static class FailedMessage {
    // ... 原有字段 ...
    
    /** 失败阶段：PUBLISH=发送失败, CONSUME=消费失败 */
    private String failureStage;  // ⭐ 新增
}
```

#### 方法签名变更

**之前：**
```java
public String saveFailedMessage(
    String messageBody,
    String exchange,
    String routingKey,
    int replyCode,
    String replyText,
    String failureType
)
```

**现在：**
```java
public String saveFailedMessage(
    String messageBody,
    String exchange,
    String routingKey,
    int replyCode,
    String replyText,
    String failureType,
    String failureStage  // ⭐ 新增参数
)
```

#### 新增查询方法

```java
// 按阶段查询
List<FailedMessage> getFailedMessagesByStage(String failureStage)

// 按阶段和状态查询
List<FailedMessage> getFailedMessagesByStageAndStatus(String failureStage, String status)

// 按阶段统计
FailureStatistics getStatisticsByStage(String failureStage)
```

---

### 2. 失败阶段和类型定义

#### 失败阶段（FailureStage）

| 阶段 | 值 | 说明 |
|------|-------|------|
| 发送阶段 | `PUBLISH` | 消息从生产者发送到 RabbitMQ 时失败 |
| 消费阶段 | `CONSUME` | 消息从 RabbitMQ 消费处理时失败 |

#### 失败类型（FailureType）

**发送阶段失败类型：**

| 类型 | 值 | 说明 | 原因 |
|------|-------|------|------|
| 路由失败 | `ROUTING_FAILED` | 消息无法路由到任何队列 | 路由键错误、队列不存在 |
| Confirm失败 | `CONFIRM_FAILED` | 消息未到达Exchange | Exchange不存在、网络问题 |

**消费阶段失败类型：**

| 类型 | 值 | 说明 | 原因 |
|------|-------|------|------|
| 消费失败 | `CONSUME_FAILED` | 通用消费失败 | 未知错误 |
| 业务错误 | `BUSINESS_ERROR` | 业务逻辑处理失败 | 业务异常、数据校验失败 |
| 消息过期 | `MESSAGE_EXPIRED` | 消息TTL超时 | 消息在队列中停留过久 |
| 队列溢出 | `QUEUE_OVERFLOW` | 队列长度超限 | 队列消息堆积过多 |

---

### 3. DeadLetterQueueConsumer 改进

**之前：**
```java
// 只有 TODO 注释，没有实际实现
private void saveFailedMessageToDatabase(Message message) {
    // TODO: 实现数据库保存逻辑
    log.info("→ [DLX Consumer] 保存失败消息到数据库: ID={}", message.getId());
}
```

**现在：**
```java
// 使用统一的失败消息服务
private void saveFailedMessageToService(Message message, List<Map<String, Object>> xDeath) {
    // 提取死信信息
    // 确定失败类型
    // 保存到 MessageFailureService
    String failedMessageId = messageFailureService.saveFailedMessage(
        messageBody,
        exchange,
        routingKey,
        replyCode,
        replyText,
        failureType,
        "CONSUME"  // ⭐ 消费阶段失败
    );
}
```

**改进点：**
1. ✅ 死信消息现在会被保存到统一的失败消息服务
2. ✅ 可以通过 API 查询和重试死信消息
3. ✅ 自动识别死信原因（rejected、expired、maxlen）

---

### 4. 新增 API 接口

#### 按阶段查询失败消息

```bash
# 查看所有发送失败的消息
GET /api/messages/failures/stage/PUBLISH

# 查看所有消费失败的消息
GET /api/messages/failures/stage/CONSUME

# 查看待处理的发送失败消息
GET /api/messages/failures/stage/PUBLISH?status=PENDING

# 查看重试耗尽的消费失败消息
GET /api/messages/failures/stage/CONSUME?status=RETRY_EXHAUSTED
```

#### 按阶段统计

```bash
# 全部统计（包含分阶段统计）
GET /api/messages/failures/statistics

# 只统计发送失败
GET /api/messages/failures/statistics?stage=PUBLISH

# 只统计消费失败
GET /api/messages/failures/statistics?stage=CONSUME
```

**响应示例：**
```json
{
  "total": 150,
  "pending": 45,
  "retrying": 8,
  "retrySuccess": 72,
  "retryExhausted": 15,
  "manuallyResolved": 10,
  "publishFailures": 90,    // ⭐ 新增
  "consumeFailures": 60     // ⭐ 新增
}
```

---

### 5. 新增常量类

创建了 `MessageFailureConstants` 类，避免硬编码字符串：

```java
// 使用常量（推荐）
messageFailureService.saveFailedMessage(
    messageBody, exchange, routingKey, replyCode, replyText,
    MessageFailureConstants.PublishFailureType.ROUTING_FAILED,
    MessageFailureConstants.FailureStage.PUBLISH
);

// 直接使用字符串（不推荐）
messageFailureService.saveFailedMessage(
    messageBody, exchange, routingKey, replyCode, replyText,
    "ROUTING_FAILED",
    "PUBLISH"
);
```

---

## 📊 数据结构对比

### 重构前

```json
{
  "id": "uuid-123",
  "messageBody": "...",
  "failureType": "ROUTING_FAILED",
  "status": "PENDING"
}
```

### 重构后

```json
{
  "id": "uuid-123",
  "messageBody": "...",
  "failureStage": "PUBLISH",      // ⭐ 新增：区分失败阶段
  "failureType": "ROUTING_FAILED",
  "status": "PENDING"
}
```

---

## 🔧 升级指南

### 如果你使用的是默认示例代码

✅ **无需任何改动**，代码已经自动更新兼容

### 如果你自定义了失败处理逻辑

#### 1. 更新 saveFailedMessage 调用

**需要修改：** 所有调用 `messageFailureService.saveFailedMessage()` 的地方

```java
// ❌ 旧代码（6个参数）
messageFailureService.saveFailedMessage(
    messageBody, exchange, routingKey, 
    replyCode, replyText, failureType
);

// ✅ 新代码（7个参数，添加 failureStage）
messageFailureService.saveFailedMessage(
    messageBody, exchange, routingKey, 
    replyCode, replyText, failureType,
    "PUBLISH"  // 或 "CONSUME"
);
```

#### 2. 更新数据库表结构（如果使用了数据库）

如果你已经用数据库替换了内存存储，需要添加字段：

```sql
-- 添加失败阶段字段
ALTER TABLE failed_messages 
ADD COLUMN failure_stage VARCHAR(20) NOT NULL DEFAULT 'PUBLISH';

-- 添加索引
CREATE INDEX idx_failure_stage ON failed_messages(failure_stage);
CREATE INDEX idx_failure_stage_status ON failed_messages(failure_stage, status);
```

#### 3. 更新前端代码（如果有管理界面）

```javascript
// 新增按阶段筛选
fetch('/api/messages/failures/stage/PUBLISH')
fetch('/api/messages/failures/stage/CONSUME')

// 统计数据新增字段
statistics.publishFailures  // 发送失败数量
statistics.consumeFailures  // 消费失败数量
```

---

## 📈 使用示例

### 场景1：查看最近的发送失败消息

```bash
# 查看所有发送失败
curl http://localhost:8080/api/messages/failures/stage/PUBLISH

# 只看待处理的
curl "http://localhost:8080/api/messages/failures/stage/PUBLISH?status=PENDING"
```

### 场景2：查看死信队列的消费失败

```bash
# 查看所有消费失败
curl http://localhost:8080/api/messages/failures/stage/CONSUME

# 只看重试耗尽的（需要人工处理）
curl "http://localhost:8080/api/messages/failures/stage/CONSUME?status=RETRY_EXHAUSTED"
```

### 场景3：监控面板展示

```bash
# 获取完整统计
curl http://localhost:8080/api/messages/failures/statistics
```

响应示例：
```json
{
  "total": 150,
  "pending": 45,
  "retrying": 8,
  "retrySuccess": 72,
  "retryExhausted": 15,
  "manuallyResolved": 10,
  "publishFailures": 90,    // 发送失败：90条
  "consumeFailures": 60     // 消费失败：60条
}
```

可以在监控面板上分别显示：
- 📤 **发送健康度**：90条失败 / 10000条发送 = 0.9% 失败率
- 📥 **消费健康度**：60条失败 / 8000条消费 = 0.75% 失败率

---

## 🎯 最佳实践

### 1. 告警策略

```java
// 按阶段设置不同的告警阈值
FailureStatistics stats = messageFailureService.getStatistics();

// 发送失败告警（通常更严重）
if (stats.getPublishFailures() > 100) {
    alertService.sendUrgentAlert("发送失败过多：" + stats.getPublishFailures());
}

// 消费失败告警
if (stats.getConsumeFailures() > 500) {
    alertService.sendAlert("消费失败过多：" + stats.getConsumeFailures());
}
```

### 2. 定时清理

```java
@Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点
public void cleanupOldFailures() {
    // 清理30天前已处理的消息
    LocalDateTime cutoffTime = LocalDateTime.now().minusDays(30);
    
    List<FailedMessage> oldMessages = messageFailureService.getAllFailedMessages()
        .stream()
        .filter(m -> m.getResolvedTime() != null)
        .filter(m -> m.getResolvedTime().isBefore(cutoffTime))
        .collect(Collectors.toList());
    
    oldMessages.forEach(m -> messageFailureService.deleteFailedMessage(m.getId()));
    
    log.info("清理了 {} 条历史失败消息", oldMessages.size());
}
```

### 3. 监控面板

建议在监控面板上分别展示：

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| 发送失败率 | `publishFailures / totalSent` | > 1% |
| 消费失败率 | `consumeFailures / totalConsumed` | > 5% |
| 待处理数量 | `pending` | > 100 |
| 重试耗尽数量 | `retryExhausted` | > 10 |

---

## ❓ 常见问题

### Q1: 为什么不拆分成两个服务？

**A:** 拆分会导致：
- ❌ 代码重复（重试、持久化、统计等功能要写两遍）
- ❌ 管理分散（需要两套API、两套界面）
- ❌ 无法统一监控系统整体健康度

统一管理的优势：
- ✅ 代码复用性好
- ✅ 统一的管理界面
- ✅ 完整的消息失败链路追踪

### Q2: 现有的失败消息会丢失吗？

**A:** 不会。

- 如果使用内存存储：重启后会丢失（这是内存存储的固有特性）
- 如果使用数据库：添加 `failure_stage` 字段即可，已有数据默认为 `PUBLISH`

### Q3: 如何区分是路由失败还是消费失败？

**A:** 通过 `failureStage` 和 `failureType` 组合判断：

| failureStage | failureType | 含义 |
|--------------|-------------|------|
| PUBLISH | ROUTING_FAILED | 发送时路由失败 |
| PUBLISH | CONFIRM_FAILED | 发送时Confirm失败 |
| CONSUME | BUSINESS_ERROR | 消费时业务处理失败 |
| CONSUME | MESSAGE_EXPIRED | 消息在队列中过期 |

### Q4: 原来的 API 还能用吗？

**A:** 可以！原有的 API 全部保留：

```bash
# ✅ 原有 API 仍然可用
GET /api/messages/failures
GET /api/messages/failures/pending
GET /api/messages/failures/{messageId}

# ✅ 新增 API
GET /api/messages/failures/stage/PUBLISH
GET /api/messages/failures/stage/CONSUME
GET /api/messages/failures/statistics?stage=PUBLISH
```

---

## 📚 相关文档

- [消息失败处理详细文档](MESSAGE_FAILURE_HANDLING.md)
- [生产环境部署指南](../PRODUCTION.md)
- [API 接口文档](../README.md#api-接口)

---

## 🔄 变更历史

| 日期 | 版本 | 变更内容 |
|------|------|----------|
| 2025-10-30 | v2.0 | 重构为统一的失败处理机制，添加 failureStage 字段 |
| 2025-10-28 | v1.0 | 初始版本，仅支持发送失败 |


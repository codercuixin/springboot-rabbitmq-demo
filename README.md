# RabbitMQ Spring Boot 异步确认示例

这是一个完整的 Spring Boot + RabbitMQ 示例项目，展示了 **Publisher 异步确认** 和 **Consumer 异步确认** 的实现。

## 📋 目录

- [功能特性](#功能特性)
- [架构设计](#架构设计)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [环境准备](#环境准备)
- [快速开始](#快速开始)
- [核心功能说明](#核心功能说明)
- [API 接口](#api-接口)
- [测试场景](#测试场景)
- [配置说明](#配置说明)

## ✨ 功能特性

### 1. Publisher 异步确认

- ✅ **ConfirmCallback**：消息到达 Exchange 时异步接收确认
- ✅ **ReturnCallback**：消息无法路由到队列时接收回调
- ✅ **CorrelationData**：使用关联数据跟踪每条消息的确认状态
- ✅ **批量发送**：支持批量发送消息并异步接收确认
- ✅ **自动重试机制** 🔥：使用 Spring Retry 自动处理临时网络问题（3次重试，指数退避）

### 2. Consumer 异步确认

- ✅ **手动确认模式**：`acknowledge-mode: manual`
- ✅ **异步处理**：使用 `CompletableFuture` 异步处理消息
- ✅ **basicAck**：消息处理成功后手动确认
- ✅ **basicNack**：消息处理失败时拒绝消息（支持重新入队）
- ✅ **流量控制**：通过 `prefetch` 控制消费速率

### 3. 消息失败处理机制 🔥 

- ✅ **三层防护机制**：Confirm 回调 → Return 回调 → Alternate Exchange
- ✅ **失败消息持久化**：自动保存路由失败和发送失败的消息
- ✅ **失败消息管理**：完整的 REST API（查询、重试、人工处理）
- ✅ **Alternate Exchange**：RabbitMQ 层面自动备份路由失败的消息
- ✅ **告警机制**：失败消息自动告警（可集成钉钉/企业微信）
- ✅ **重试机制**：支持手动重试和批量重试
- ✅ **状态管理**：PENDING → RETRYING → SUCCESS/EXHAUSTED
- ✅ **统计分析**：失败消息统计和监控

### 4. 生产级特性 🚀

- ✅ **死信队列（DLX）**：自动处理失败消息
- ✅ **延迟重试队列**：支持消息自动重试（可配置重试次数和延迟时间）
- ✅ **消息幂等性**：使用缓存防止重复消费
- ✅ **连接池优化**：Channel 缓存和连接复用
- ✅ **优雅关闭**：确保消息不丢失
- ✅ **完善的异常处理**：多层次错误处理机制
- ✅ **单元测试和集成测试**：完整的测试覆盖

## 🏗️ 架构设计

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          应用层 (Spring Boot)                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌──────────────┐         ┌──────────────┐       ┌────────────────┐ │
│  │   REST API   │         │  Publisher   │       │   Consumer     │ │
│  │  Controller  │────────▶│   (发送者)   │       │   (消费者)     │ │
│  └──────────────┘         └──────┬───────┘       └────────▲───────┘ │
│                                  │                         │         │
│                                  │                         │         │
└──────────────────────────────────┼─────────────────────────┼─────────┘
                                   │                         │
                                   ▼                         │
┌─────────────────────────────────────────────────────────────────────┐
│                       RabbitMQ Broker                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    主业务交换机                               │  │
│  │                  (demo.exchange)                              │  │
│  │              [配置 Alternate Exchange]                        │  │
│  └──────┬─────────────────────────────────┬─────────────────────┘  │
│         │ 成功路由                    路由失败│                       │
│         ▼                                   ▼                       │
│  ┌─────────────┐                    ┌────────────────┐             │
│  │ 主业务队列   │                    │  备用交换机     │             │
│  │demo.queue   │                    │alternate.ex    │             │
│  │[配置DLX]    │                    │  (Fanout)      │             │
│  └─────┬───────┘                    └───────┬────────┘             │
│        │                                    │                       │
│        │ 消息处理失败                        ▼                       │
│        │ (basicNack)              ┌──────────────────┐             │
│        │                          │ 未路由消息队列    │             │
│        ▼                          │unrouted.queue    │             │
│  ┌─────────────┐                 └─────────▲────────┘             │
│  │ 死信交换机   │                           │                       │
│  │dlx.exchange │                           │                       │
│  └─────┬───────┘                           │                       │
│        │                                    │                       │
│        ▼                                    │                       │
│  ┌─────────────┐      ┌──────────────┐    │                       │
│  │ 死信队列     │      │ 延迟重试队列  │    │                       │
│  │ dlx.queue   │      │ retry.queue  │    │                       │
│  └─────────────┘      └──────────────┘    │                       │
│                                            │                       │
└────────────────────────────────────────────┼───────────────────────┘
                                             │
                                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      失败消息处理层                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │            MessageFailureService (统一管理)                 │    │
│  │     - 发送失败（PUBLISH）: 路由失败、Confirm失败            │    │
│  │     - 消费失败（CONSUME）: 业务失败、死信消息               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │         UnroutedMessageConsumer                             │    │
│  │       (处理从 Alternate Exchange 来的消息)                  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

### 消息流转过程

#### 1️⃣ 正常流程
```
发送消息 → Confirm回调(ACK) → 路由到队列 → Consumer消费 → basicAck → 完成
```

#### 2️⃣ 路由失败流程（三层防护）
```
发送消息 → Confirm回调(ACK) → 路由失败
    ↓
Layer 1: Return 回调
    ├─ 记录日志
    ├─ 持久化到 MessageFailureService
    └─ 发送告警
    ↓
Layer 2: Alternate Exchange
    ├─ RabbitMQ 自动转发
    └─ 路由到 unrouted.messages.queue
    ↓
Layer 3: UnroutedMessageConsumer
    ├─ 消费处理
    ├─ 分析原因
    └─ 尝试修复或人工介入
```

#### 3️⃣ 消费失败流程
```
Consumer消费 → 处理失败 → basicNack(requeue=false)
    ↓
死信交换机 (DLX)
    ↓
死信队列 (dlx.queue)
    ↓
DeadLetterQueueConsumer 处理
    ├─ 记录日志
    ├─ 持久化到 MessageFailureService (failureStage=CONSUME)
    ├─ 分析原因
    └─ 人工介入或重试
```

#### 4️⃣ 延迟重试流程
```
Consumer消费 → 需要重试 → 发送到延迟重试队列
    ↓
等待 TTL 过期（5秒）
    ↓
自动转发回主业务队列
    ↓
重新消费
```

### 核心组件说明

#### Publisher（消息发布者）
- **MessagePublisher.java**
- 职责：发送消息、接收确认回调
- 特性：
  - Publisher Confirm 异步确认
  - Return 回调处理路由失败
  - 失败消息持久化
  - 告警通知

#### Consumer（消息消费者）
- **ProductionMessageConsumer.java** - 主业务消费者
- **DeadLetterQueueConsumer.java** - 死信队列消费者
- **UnroutedMessageConsumer.java** - 未路由消息消费者
- 职责：消费消息、手动确认、异常处理
- 特性：
  - 手动确认模式
  - 幂等性保证
  - 重试机制
  - 异常降级

#### 失败消息管理
- **MessageFailureService.java**
- 职责：失败消息持久化、重试管理、统计分析
- 功能：
  - 保存失败消息（ROUTING_FAILED、CONFIRM_FAILED）
  - 状态管理（PENDING → RETRYING → SUCCESS/EXHAUSTED）
  - 批量重试
  - 人工处理

#### 配置管理
- **RabbitMQConfig.java** - 队列、交换机、绑定配置
- **RabbitMQConnectionConfig.java** - 连接池、性能优化
- 特性：
  - 主业务队列配置（含 DLX）
  - 死信队列配置
  - 延迟重试队列配置
  - Alternate Exchange 配置

### 可靠性保证机制

#### 1. 消息不丢失
- ✅ Publisher Confirm：确保消息到达 Broker
- ✅ 持久化：消息、队列、交换机都持久化
- ✅ 手动确认：消费成功后才确认
- ✅ 死信队列：处理失败的消息不丢失

#### 2. 消息不重复
- ✅ 消息 ID：每条消息唯一标识
- ✅ 幂等性检查：MessageIdempotentService
- ✅ 缓存去重：Guava Cache

#### 3. 消息有序性
- ✅ Prefetch=1：单线程顺序处理
- ✅ 单个 Consumer：保证同一队列有序

#### 4. 故障恢复
- ✅ 自动重连：Spring AMQP 自动重连机制
- ✅ 消息备份：Alternate Exchange 自动备份
- ✅ 降级策略：多种失败处理方案

### 性能优化

#### 1. 连接池优化
```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 25          # Channel 缓存大小
        checkout-timeout: 0
      connection:
        mode: channel     # 连接模式
```

#### 2. 并发处理
```yaml
listener:
  simple:
    concurrency: 3        # 最小并发消费者
    max-concurrency: 10   # 最大并发消费者
    prefetch: 1          # 预取数量
```

#### 3. 批量操作
- 批量发送消息
- 批量重试失败消息
- 批量确认

### 监控指标

#### 应用层监控
- 消息发送成功率
- 消息消费成功率
- 失败消息数量
- 重试成功率
- 平均处理时间

#### RabbitMQ 监控
- 队列深度
- 消息速率
- 连接数/通道数
- 内存使用率
- 磁盘使用率

#### 失败消息监控
- 待处理消息数 (PENDING)
- 重试中消息数 (RETRYING)
- 重试耗尽消息数 (RETRY_EXHAUSTED)
- 成功处理消息数 (RETRY_SUCCESS)

## 🛠 技术栈

- **Spring Boot**: 3.2.0
- **Spring AMQP**: RabbitMQ 集成
- **Spring Retry**: 自动重试机制
- **Java**: 17
- **Lombok**: 简化代码
- **Maven**: 依赖管理
- **Guava**: 本地缓存（幂等性）
- **Spring Boot Actuator**: 健康检查和监控

## 📁 项目结构

```
rabbitmq-spring/
├── src/main/java/com/example/rabbitmq/
│   ├── RabbitMQApplication.java                  # 主程序入口
│   ├── config/
│   │   ├── RabbitMQConfig.java                   # RabbitMQ 配置（包含 Alternate Exchange）
│   │   └── RabbitMQConnectionConfig.java         # 连接池和性能配置
│   ├── publisher/
│   │   └── MessagePublisher.java                 # 消息发布者（增强版：失败处理）
│   ├── consumer/
│   │   ├── ProductionMessageConsumer.java        # 生产级消费者（完整特性）
│   │   ├── DeadLetterQueueConsumer.java          # 死信队列消费者
│   │   └── UnroutedMessageConsumer.java          # 未路由消息消费者（NEW）
│   ├── service/
│   │   ├── MessageIdempotentService.java         # 消息幂等性服务
│   │   └── MessageFailureService.java            # 失败消息管理服务（NEW）
│   ├── controller/
│   │   └── MessageController.java                # REST API 控制器（增强版）
│   └── model/
│       └── Message.java                          # 消息实体类
├── src/main/resources/
│   ├── application.yml                           # 默认配置
│   ├── application-dev.yml                       # 开发环境配置
│   └── application-prod.yml                      # 生产环境配置
├── src/test/                                     # 测试代码
├── docs/
│   ├── MESSAGE_FAILURE_HANDLING.md               # 消息失败处理详细文档（NEW）
│   └── CONFIG_STRUCTURE.md                       # 配置说明文档
├── pom.xml                                       # Maven 配置
├── README.md                                     # 项目说明文档（本文档）
├── ARCHITECTURE.md                               # 架构设计文档
├── PRODUCTION.md                                 # 生产环境部署指南
└── QUICKSTART.md                                 # 快速开始指南
```

## 🚀 环境准备

### 1. 安装 RabbitMQ

**使用 Docker 快速启动（推荐）：**

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  rabbitmq:3-management
```

**或者使用 Homebrew（macOS）：**

```bash
brew install rabbitmq
brew services start rabbitmq
```

### 2. 访问 RabbitMQ 管理界面

打开浏览器访问：http://localhost:15672

- 用户名：`guest`
- 密码：`guest`

## 🏃 快速开始

### 1. 克隆或下载项目

```bash
cd /Users/cuixin/work/rabbitmq-spring
```

### 2. 编译项目

```bash
mvn clean package
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

或者运行编译后的 JAR：

```bash
java -jar target/rabbitmq-spring-1.0.0.jar
```

### 4. 验证启动

```bash
curl http://localhost:8080/api/messages/health
```

应该返回：`RabbitMQ Service is running!`

## 🔍 核心功能说明

### 1. Publisher Confirm 异步确认

**实现位置**：`MessagePublisher.java`

#### 关键代码：

```java
// 配置 Confirm 回调（处理发送失败）
rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
    if (ack) {
        log.info("✓ 消息发送成功！消息ID: {}", correlationData.getId());
    } else {
        log.error("✗ 消息发送失败！消息ID: {}, 原因: {}", 
                correlationData.getId(), cause);
        // ✅ 持久化失败消息
        handleConfirmFailure(correlationData, cause);
    }
});

// 配置 Return 回调（处理路由失败）
rabbitTemplate.setReturnsCallback(returned -> {
    log.error("✗ 消息路由失败！消息: {}, 交换机: {}, 路由键: {}",
            returned.getMessage(),
            returned.getExchange(),
            returned.getRoutingKey());
    
    // ✅ 持久化失败消息
    messageFailureService.saveFailedMessage(...);
    
    // ✅ 发送告警
    sendAlert(returned);
});
```

#### 三层防护机制：

```
发送消息
    ↓
┌─────────────────────────────┐
│ Layer 1: Confirm 回调        │  消息是否到达 Exchange
│ - 失败则持久化记录          │
└─────────────────────────────┘
    ↓
┌─────────────────────────────┐
│ Layer 2: Return 回调         │  消息是否成功路由
│ - 持久化 + 告警             │
└─────────────────────────────┘
    ↓
┌─────────────────────────────┐
│ Layer 3: Alternate Exchange  │  RabbitMQ 自动备份
│ - 转发到备用队列           │
└─────────────────────────────┘
```

### 2. 消息失败处理机制 🔥 

**实现位置**：`MessageFailureService.java`

#### 失败消息管理：

```java
// 保存失败消息
String failedMessageId = messageFailureService.saveFailedMessage(
    messageBody,
    exchange,
    routingKey,
    replyCode,
    replyText,
    "ROUTING_FAILED"  // 或 "CONFIRM_FAILED"
);

// 查询失败消息
List<FailedMessage> failedMessages = messageFailureService.getAllFailedMessages();

// 重试失败消息
messageFailureService.markAsRetrying(messageId);
// ... 重新发送消息 ...
messageFailureService.markAsRetrySuccess(messageId);

// 人工处理
messageFailureService.markAsManuallyResolved(messageId, "admin", "已手动处理");
```

#### 消息状态流转：

```
PENDING → RETRYING → RETRY_SUCCESS
                  ↓
              RETRY_EXHAUSTED → MANUALLY_RESOLVED
```

### 3. Alternate Exchange（备用交换机）🔥

**实现位置**：`RabbitMQConfig.java`

#### 配置：

```java
// 主交换机配置备用交换机
@Bean
public DirectExchange demoExchange() {
    return ExchangeBuilder.directExchange(EXCHANGE_NAME)
            .durable(true)
            .alternate(ALTERNATE_EXCHANGE_NAME)  // 关键配置
            .build();
}

// 备用交换机（Fanout 类型）
@Bean
public FanoutExchange alternateExchange() {
    return new FanoutExchange(ALTERNATE_EXCHANGE_NAME, true, false);
}
```

#### 工作原理：

1. 主交换机无法路由消息时
2. 消息自动转发到 Alternate Exchange
3. Alternate Exchange 广播到所有绑定的队列
4. UnroutedMessageConsumer 消费处理

### 4. Consumer 异步确认

**实现位置**：`ProductionMessageConsumer.java`

#### 关键代码：

```java
@RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
public void receiveMessage(@Payload Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    
        try {
            // 业务处理
            processMessage(message);
            
            // 手动确认消息
            channel.basicAck(deliveryTag, false);
            log.info("✓ 消息处理成功，已确认");
            
        } catch (Exception e) {
            // 拒绝消息
            channel.basicNack(deliveryTag, false, false);
            log.error("✗ 消息处理失败，已拒绝");
        }
}
```

#### 工作流程：

1. 消费者从队列接收消息（但不自动确认）
2. 处理成功：调用 `channel.basicAck()` 手动确认
3. 处理失败：调用 `channel.basicNack()` 拒绝消息

## 🌐 API 接口

### 消息发送接口

#### 1. 发送单条消息

```bash
curl -X POST http://localhost:8080/api/messages/send \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello RabbitMQ"}'
```

#### 2. 发送消息（带自动重试）🔥 **推荐使用**

```bash
curl -X POST http://localhost:8080/api/messages/send-with-retry \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello with Retry"}'
```

**特性：**
- ✅ 自动处理临时网络问题
- ✅ 重试3次，指数退避（1s、2s、4s）
- ✅ 失败后自动保存到失败记录

**响应示例（成功）：**
```json
{
  "success": true,
  "messageId": "abc-123-xyz",
  "message": "消息发送成功（带自动重试保护）"
}
```

**响应示例（失败）：**
```json
{
  "success": false,
  "message": "消息发送失败（重试3次后仍然失败，已保存到失败记录）"
}
```

#### 3. 发送消息并等待确认

```bash
curl -X POST http://localhost:8080/api/messages/send-with-confirm \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello with Confirm"}'
```

#### 4. 批量发送消息

```bash
curl -X POST "http://localhost:8080/api/messages/send-batch?count=10"
```

#### 5. 发送错误消息（测试 Consumer 异常处理）

```bash
curl -X POST http://localhost:8080/api/messages/send-error
```

#### 6. 发送到错误路由键（测试 Return 回调）

```bash
curl -X POST http://localhost:8080/api/messages/send-wrong-routing
```

### 失败消息管理接口 🔥 **NEW**

#### 7. 获取所有失败消息

```bash
curl http://localhost:8080/api/messages/failures
```

**响应示例：**
```json
{
  "total": 5,
  "statistics": {
    "total": 5,
    "pending": 3,
    "retrying": 1,
    "retrySuccess": 1,
    "retryExhausted": 0,
    "manuallyResolved": 0
  },
  "messages": [...]
}
```

#### 7. 获取待处理的失败消息

```bash
curl http://localhost:8080/api/messages/failures/pending
```

#### 8. 获取失败消息统计

```bash
curl http://localhost:8080/api/messages/failures/statistics
```

#### 9. 重试单个失败消息

```bash
# 替换 {messageId} 为实际的消息ID
curl -X POST http://localhost:8080/api/messages/failures/{messageId}/retry
```

#### 10. 批量重试失败消息

```bash
curl -X POST "http://localhost:8080/api/messages/failures/retry-batch?limit=10"
```

#### 11. 人工标记消息为已处理

```bash
curl -X POST http://localhost:8080/api/messages/failures/{messageId}/resolve \
  -H "Content-Type: application/json" \
  -d '{
    "resolvedBy": "admin",
    "note": "已手动修复并重新发送"
  }'
```

#### 12. 删除失败消息记录

```bash
curl -X DELETE http://localhost:8080/api/messages/failures/{messageId}
```

## 🧪 测试场景

### 场景 1：正常消息流程

1. 发送消息
2. Publisher 收到 Confirm 确认
3. Consumer 接收并处理消息
4. Consumer 手动确认消息

**日志输出示例**：

```
→ [Publisher] 准备发送消息: Hello RabbitMQ, ID: xxx
→ [Publisher] 消息已发送，等待 Broker 确认...
✓ [Publisher Confirm] 消息发送成功！消息ID: xxx
← [Consumer] 收到消息 #1: ID=xxx, Content=Hello RabbitMQ
→ [Consumer] 开始处理消息: Hello RabbitMQ
→ [Consumer] 消息处理完成: Hello RabbitMQ
✓ [Consumer] 消息处理成功，已确认: ID=xxx
```

### 场景 2：消息处理失败

发送包含 "error" 的消息：

```bash
curl -X POST http://localhost:8080/api/messages/send-error
```

**日志输出**：

```
✗ [Consumer] 消息处理失败: ID=xxx, Error=消息内容包含错误标识
✗ [Consumer] 消息已拒绝，不重新入队: ID=xxx
```

### 场景 3：消息路由失败（完整流程）🔥 **NEW**

```bash
curl -X POST http://localhost:8080/api/messages/send-wrong-routing
```

**日志输出**：

```
✓ [Publisher Confirm] 消息发送成功！消息ID: xxx
✗ [Publisher Return] 消息路由失败！
  交换机: demo.exchange
  路由键: wrong.routing.key
  响应码: 312
✓ 失败消息已持久化，ID: yyy，可通过管理接口重试
⚠ 告警信息: 【RabbitMQ告警】消息路由失败...
⚠ [UnroutedConsumer] 收到未路由消息
  消息内容: {...}
  原始交换机: demo.exchange
  原始路由键: wrong.routing.key
```

**三层防护工作流程**：
1. ✅ Return 回调记录失败消息
2. ✅ 持久化到 MessageFailureService
3. ✅ Alternate Exchange 自动转发到备用队列
4. ✅ UnroutedMessageConsumer 消费处理

### 场景 4：失败消息管理 🔥 **NEW**

#### 4.1 查看失败消息

```bash
# 查看所有失败消息
curl http://localhost:8080/api/messages/failures

# 只查看发送失败的消息（路由失败、Confirm失败）
curl http://localhost:8080/api/messages/failures/stage/PUBLISH

# 只查看消费失败的消息（死信队列、业务处理失败）
curl http://localhost:8080/api/messages/failures/stage/CONSUME

# 查看统计信息
curl http://localhost:8080/api/messages/failures/statistics
```

**响应示例**：
```json
{
  "total": 150,
  "pending": 45,
  "retrying": 8,
  "retrySuccess": 72,
  "retryExhausted": 15,
  "manuallyResolved": 10,
  "publishFailures": 90,    // 发送阶段失败数量
  "consumeFailures": 60     // 消费阶段失败数量
}
```

**失败阶段说明**：

| 阶段 | 值 | 包含的失败类型 |
|------|-------|----------------|
| 发送阶段 | `PUBLISH` | 路由失败、Confirm失败 |
| 消费阶段 | `CONSUME` | 业务处理失败、死信消息、消息过期、队列溢出 |

#### 4.2 重试失败消息

```bash
# 获取待处理的失败消息
curl http://localhost:8080/api/messages/failures/pending

# 重试指定消息（使用返回的消息ID）
curl -X POST http://localhost:8080/api/messages/failures/abc-123/retry

# 批量重试
curl -X POST "http://localhost:8080/api/messages/failures/retry-batch?limit=10"
```

#### 4.3 人工处理

```bash
# 标记为已处理
curl -X POST http://localhost:8080/api/messages/failures/abc-123/resolve \
  -H "Content-Type: application/json" \
  -d '{
    "resolvedBy": "admin",
    "note": "已确认问题，手动重新发送"
  }'
```

## ⚙️ 配置说明

### application.yml 关键配置

```yaml
spring:
  rabbitmq:
    # Publisher Confirm 配置
    publisher-confirm-type: correlated  # 启用关联确认（异步）
    publisher-returns: true              # 启用消息返回回调
    template:
      mandatory: true                    # 必须路由到队列（触发 Return 回调）
    
    # Consumer 配置
    listener:
      simple:
        acknowledge-mode: manual  # 手动确认模式
        prefetch: 1              # 每次只拉取 1 条消息
        concurrency: 3           # 并发消费者数量
```

### RabbitMQ 配置详解

#### 1. Publisher Confirm Type 选项

| 选项 | 说明 | 使用场景 |
|-----|------|---------|
| `none` | 禁用 Publisher Confirm | 不关心消息是否成功发送 |
| `simple` | 同步等待确认（阻塞） | 需要立即知道发送结果 |
| `correlated` | 异步确认（推荐）| 高性能场景 ✅ |

#### 2. Acknowledge Mode 选项

| 模式 | 说明 | 使用场景 |
|-----|------|---------|
| `auto` | 自动确认 | 消息处理简单，不需要重试 |
| `manual` | 手动确认（推荐）| 需要精确控制消息确认 ✅ |
| `none` | 不确认 | 测试或特殊场景 |

#### 3. Prefetch 说明

| 值 | 说明 | 适用场景 |
|---|------|---------|
| `1` | 每次拉取 1 条 | 消息处理耗时长，需要严格顺序 ✅ |
| `10-50` | 每次拉取多条 | 消息处理快，提高吞吐量 |
| `0` | 无限制 | ❌ 不推荐，可能导致内存溢出 |

#### 4. Alternate Exchange 配置

```java
// 主交换机配置备用交换机
@Bean
public DirectExchange demoExchange() {
    return ExchangeBuilder.directExchange(EXCHANGE_NAME)
            .durable(true)
            .alternate(ALTERNATE_EXCHANGE_NAME)  // 🔥 关键配置
            .build();
}
```

**作用**：当消息无法路由时，自动转发到备用交换机，实现 RabbitMQ 层面的消息备份。

## 📊 监控与调试

### 1. 查看 RabbitMQ 管理界面

访问：http://localhost:15672

- 查看队列消息数量
- 监控消息速率
- 查看连接和通道
- 查看 Alternate Exchange 配置

**关键队列：**
- `demo.queue` - 主业务队列
- `dlx.queue` - 死信队列
- `demo.retry.queue` - 延迟重试队列
- `unrouted.messages.queue` - 未路由消息队列（NEW）

### 2. 失败消息监控 🔥 **NEW**

```bash
# 查看失败消息统计
curl http://localhost:8080/api/messages/failures/statistics

# 查看待处理的失败消息
curl http://localhost:8080/api/messages/failures/pending
```

**监控指标：**
- `total` - 失败消息总数
- `pending` - 待处理数量
- `retrying` - 重试中数量
- `retryExhausted` - 重试耗尽数量（需人工处理）

### 3. 查看应用日志

```bash
tail -f logs/spring.log
```

### 4. 关键日志标识

- `→` ：开始操作
- `←` ：接收消息
- `✓` ：成功
- `✗` ：失败
- `⟳` ：重试
- `⚠` ：告警（NEW）

## 🔥 常见问题

### Q1: 消息发送成功但没有消费？

**原因**：可能是队列未正确绑定到交换机，或路由键不匹配。

**解决**：
1. 检查 `RabbitMQConfig.java` 中的绑定关系
2. 查看 RabbitMQ 管理界面的 Bindings
3. 查看 `unrouted.messages.queue` 是否有消息（路由失败会进入此队列）

### Q2: Consumer 不确认消息会怎样？

**结果**：消息会一直保持 "Unacked" 状态，直到连接断开，然后重新入队。

### Q3: 如何实现消息重试？

**方案**：
1. 使用 `basicNack(deliveryTag, false, true)` 重新入队
2. 配置死信队列（DLX）
3. 使用延迟队列插件
4. 🔥 **NEW**: 使用失败消息管理接口手动重试

参考 `ProductionMessageConsumer.java` 中的重试示例。

### Q4: Prefetch 应该设置为多少？

**建议**：
- 消息处理耗时短：设置较大值（如 10-50）
- 消息处理耗时长：设置较小值（如 1-5）
- 需要严格顺序处理：设置为 1

### Q5: 消息路由失败后会丢失吗？🔥 **NEW**

**答案**：不会！本项目实现了三层防护机制：

1. **Return 回调**：持久化失败消息到 `MessageFailureService`
2. **Alternate Exchange**：RabbitMQ 自动转发到备用队列
3. **UnroutedMessageConsumer**：消费并处理失败消息

即使应用崩溃，Alternate Exchange 也会保证消息不丢失。

### Q6: 如何处理大量失败消息？🔥 **NEW**

**方案**：

1. **批量重试**：
```bash
curl -X POST "http://localhost:8080/api/messages/failures/retry-batch?limit=100"
```

2. **定时任务自动重试**（生产环境建议）：
```java
@Scheduled(fixedRate = 60000)
public void autoRetryFailedMessages() {
    // 自动重试待处理的失败消息
}
```

3. **人工介入处理**：
```bash
curl -X POST http://localhost:8080/api/messages/failures/{id}/resolve \
  -d '{"resolvedBy": "admin", "note": "已手动处理"}'
```

### Q7: 失败消息保存在哪里？🔥 **NEW**

**当前实现**：保存在内存中（`ConcurrentHashMap`）

**生产环境建议**：
- 使用数据库持久化（MySQL、PostgreSQL）
- 使用 Redis（高性能场景）
- 定期清理已处理的消息

详见：[消息失败处理文档](docs/MESSAGE_FAILURE_HANDLING.md)

## 📚 相关文档

### 官方文档

- [RabbitMQ 官方文档](https://www.rabbitmq.com/documentation.html)
- [Spring AMQP 文档](https://docs.spring.io/spring-amqp/reference/)
- [Publisher Confirms](https://www.rabbitmq.com/confirms.html)
- [Consumer Acknowledgements](https://www.rabbitmq.com/confirms.html#consumer-acknowledgements)
- [Alternate Exchanges](https://www.rabbitmq.com/ae.html)

## 👤 作者

CuiXin

## 🎯 下一步

### 快速体验

1. **启动项目**：`mvn spring-boot:run`
2. **测试路由失败**：
```bash
curl -X POST http://localhost:8080/api/messages/send-wrong-routing
```
3. **查看失败消息**：
```bash
curl http://localhost:8080/api/messages/failures
```
4. **重试失败消息**：
```bash
curl -X POST http://localhost:8080/api/messages/failures/{messageId}/retry
```

### 深入学习

- 📖 阅读 [消息失败处理文档](docs/MESSAGE_FAILURE_HANDLING.md)
- 📖 查看 [架构设计文档](ARCHITECTURE.md)
- 📖 参考 [生产环境部署指南](PRODUCTION.md)

### 生产环境准备

- [ ] 实现数据库持久化（替换内存存储）
- [ ] 集成告警系统（钉钉/企业微信）
- [ ] 添加定时任务自动重试
- [ ] 配置 Grafana 监控面板
- [ ] 添加权限控制

## 📝 许可

MIT License

---

**Happy Coding! 🎉**

> 💡 **提示**：本项目实现了生产级别的消息失败处理机制，包括三层防护、自动备份、失败重试等功能。详见 [消息失败处理文档](docs/MESSAGE_FAILURE_HANDLING.md)。


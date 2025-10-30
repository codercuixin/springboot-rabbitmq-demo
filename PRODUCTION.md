# 生产环境部署指南

## 📋 目录

- [环境要求](#环境要求)
- [配置说明](#配置说明)
- [部署步骤](#部署步骤)
- [监控和告警](#监控和告警)
- [故障排查](#故障排查)
- [性能优化](#性能优化)
- [安全建议](#安全建议)

## 🔧 环境要求

### 1. RabbitMQ 服务器

- **版本**: RabbitMQ 3.11+ 推荐
- **Erlang**: 25.0+ 推荐
- **集群**: 建议生产环境使用集群模式（3个节点）
- **插件**: 
  - `rabbitmq_management` - 管理界面
  - `rabbitmq_prometheus` - Prometheus 监控

### 2. 应用服务器

- **Java**: JDK 17+
- **内存**: 最低 2GB，推荐 4GB+
- **CPU**: 最低 2 核，推荐 4 核+
- **网络**: 稳定的网络连接，低延迟

## ⚙️ 配置说明

### 1. RabbitMQ 配置

#### 启用镜像队列（高可用）

```bash
# 设置策略：所有队列镜像到所有节点
rabbitmqctl set_policy ha-all "^" '{"ha-mode":"all","ha-sync-mode":"automatic"}'
```

#### 资源限制

```bash
# 内存高水位（80%）
rabbitmqctl set_vm_memory_high_watermark 0.8

# 磁盘空闲空间告警阈值（50GB）
rabbitmqctl set_disk_free_limit 50GB
```

### 2. 应用配置

创建 `application-prod.yml`:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
    virtual-host: ${RABBITMQ_VHOST:/prod}
    
    # 连接池配置
    cache:
      channel:
        size: 25
        checkout-timeout: 2000
    
    # 消费者配置
    listener:
      simple:
        concurrency: 5
        max-concurrency: 20
        prefetch: 5
```

### 3. 环境变量

创建 `.env` 文件：

```bash
# RabbitMQ 配置
RABBITMQ_HOST=rabbitmq-cluster.prod.example.com
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=prod_user
RABBITMQ_PASSWORD=SecurePassword123!
RABBITMQ_VHOST=/prod

# 应用配置
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
```

## 🚀 部署步骤

### 1. 构建应用

```bash
# 清理并构建
mvn clean package -DskipTests

# 或者包含测试
mvn clean package
```

### 2. Docker 部署（推荐）

创建 `Dockerfile`:

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/rabbitmq-spring-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

构建和运行：

```bash
# 构建镜像
docker build -t rabbitmq-spring:1.0.0 .

# 运行容器
docker run -d \
  --name rabbitmq-spring \
  -p 8080:8080 \
  --env-file .env \
  rabbitmq-spring:1.0.0
```

### 3. Kubernetes 部署

创建 `k8s-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rabbitmq-spring
spec:
  replicas: 3
  selector:
    matchLabels:
      app: rabbitmq-spring
  template:
    metadata:
      labels:
        app: rabbitmq-spring
    spec:
      containers:
      - name: rabbitmq-spring
        image: rabbitmq-spring:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: RABBITMQ_HOST
          valueFrom:
            secretKeyRef:
              name: rabbitmq-secret
              key: host
        - name: RABBITMQ_USERNAME
          valueFrom:
            secretKeyRef:
              name: rabbitmq-secret
              key: username
        - name: RABBITMQ_PASSWORD
          valueFrom:
            secretKeyRef:
              name: rabbitmq-secret
              key: password
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

部署：

```bash
kubectl apply -f k8s-deployment.yaml
```

## 📊 监控和告警

### 1. Actuator 端点

访问健康检查：

```bash
curl http://localhost:8080/actuator/health
```

### 2. Prometheus 指标

配置 Prometheus 抓取：

```yaml
scrape_configs:
  - job_name: 'rabbitmq-spring'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

### 3. 关键指标

- `rabbitmq.messages.sent` - 发送消息总数
- `rabbitmq.messages.received` - 接收消息总数
- `rabbitmq.messages.failed` - 失败消息总数
- `rabbitmq.message.processing.time` - 消息处理时间

### 4. Grafana 仪表板

导入 RabbitMQ 相关的 Grafana 仪表板：
- [RabbitMQ Overview](https://grafana.com/grafana/dashboards/10991)

## 🔍 故障排查

### 1. 连接问题

**症状**: 无法连接到 RabbitMQ

**检查**:
```bash
# 检查 RabbitMQ 状态
rabbitmqctl status

# 检查网络连接
telnet rabbitmq-host 5672

# 查看应用日志
tail -f logs/rabbitmq-spring-prod.log | grep ERROR
```

### 2. 消息堆积

**症状**: 队列中消息大量堆积

**解决方案**:
1. 增加消费者并发数
2. 优化消息处理逻辑
3. 扩展消费者实例

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        concurrency: 10  # 增加并发
        max-concurrency: 30
```

### 3. 内存不足

**症状**: RabbitMQ 内存告警

**解决方案**:
1. 增加服务器内存
2. 调整内存高水位
3. 启用消息持久化到磁盘

### 4. 死信队列过多

**症状**: 死信队列消息堆积

**处理**:
1. 查看死信队列日志
2. 分析失败原因
3. 修复问题后手动重新处理

## ⚡ 性能优化

### 1. 连接池优化

```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 50  # 增加 Channel 缓存
        checkout-timeout: 5000
```

### 2. 批量处理

使用批量确认提高吞吐量：

```java
// 在 Consumer 中使用批量确认
channel.basicAck(deliveryTag, true);  // multiple=true
```

### 3. Prefetch 调优

根据消息处理时间调整：

- **快速处理（<100ms）**: prefetch = 50-100
- **中速处理（100ms-1s）**: prefetch = 10-50
- **慢速处理（>1s）**: prefetch = 1-10

### 4. 消息持久化

只对关键消息启用持久化：

```java
// 发送持久化消息
rabbitTemplate.convertAndSend(exchange, routingKey, message, msg -> {
    msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    return msg;
});
```

## 🔒 安全建议

### 1. 认证和授权

- 使用强密码
- 为每个应用创建独立的用户
- 设置最小权限原则

```bash
# 创建用户
rabbitmqctl add_user prod_user SecurePassword123!

# 设置权限
rabbitmqctl set_permissions -p /prod prod_user ".*" ".*" ".*"
```

### 2. TLS/SSL 加密

启用 TLS 连接：

```yaml
spring:
  rabbitmq:
    ssl:
      enabled: true
      algorithm: TLSv1.2
```

### 3. 网络隔离

- 使用防火墙限制访问
- 使用 VPN 或专用网络
- 启用 IP 白名单

### 4. 日志审计

启用详细的审计日志：

```yaml
logging:
  level:
    com.example.rabbitmq: INFO
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"
```

## 📝 维护清单

### 日常维护

- [ ] 检查 RabbitMQ 集群状态
- [ ] 监控队列消息数量
- [ ] 查看死信队列
- [ ] 检查磁盘空间
- [ ] 查看错误日志

### 定期维护（每周）

- [ ] 分析性能指标
- [ ] 清理过期数据
- [ ] 更新文档
- [ ] 备份配置

### 月度维护

- [ ] 审查安全配置
- [ ] 性能调优
- [ ] 容量规划
- [ ] 灾难恢复演练

## 🆘 紧急联系

如遇紧急问题，请联系：

- **运维团队**: ops@example.com
- **开发团队**: dev@example.com
- **值班电话**: +86-xxx-xxxx-xxxx

---

**最后更新**: 2025-10-30


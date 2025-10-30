#!/bin/bash

# RabbitMQ Spring Boot API 测试脚本
# 使用方法: ./test-api.sh

BASE_URL="http://localhost:8080/api/messages"

echo "======================================"
echo "RabbitMQ Spring Boot API 测试脚本"
echo "======================================"
echo ""

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 健康检查
echo -e "${YELLOW}1. 健康检查${NC}"
curl -s "$BASE_URL/health"
echo -e "\n"

sleep 1

# 2. 发送单条消息
echo -e "${YELLOW}2. 发送单条消息${NC}"
curl -s -X POST "$BASE_URL/send" \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello RabbitMQ - Test Message 1"}'
echo -e "\n"

sleep 2

# 3. 发送消息并等待确认
echo -e "${YELLOW}3. 发送消息并等待确认${NC}"
curl -s -X POST "$BASE_URL/send-with-confirm" \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello with Confirm - Test Message 2"}'
echo -e "\n"

sleep 2

# 4. 批量发送消息
echo -e "${YELLOW}4. 批量发送 5 条消息${NC}"
curl -s -X POST "$BASE_URL/send-batch?count=5"
echo -e "\n"

sleep 3

# 5. 发送错误消息（触发 Consumer 异常）
echo -e "${YELLOW}5. 发送错误消息（测试 Consumer 异常处理）${NC}"
curl -s -X POST "$BASE_URL/send-error"
echo -e "\n"

sleep 2

# 6. 发送到错误路由键（触发 Return 回调）
echo -e "${YELLOW}6. 发送到错误路由键（测试 Return 回调）${NC}"
curl -s -X POST "$BASE_URL/send-wrong-routing"
echo -e "\n"

sleep 2

# 7. 再发送几条正常消息
echo -e "${YELLOW}7. 发送多条正常消息${NC}"
for i in {1..3}
do
  echo "发送消息 #$i"
  curl -s -X POST "$BASE_URL/send" \
    -H "Content-Type: application/json" \
    -d "{\"content\": \"Normal Message #$i\"}"
  echo ""
  sleep 1
done

echo ""
echo -e "${GREEN}======================================"
echo "测试完成！"
echo "=====================================${NC}"
echo ""
echo "请查看应用日志以观察："
echo "  - Publisher Confirm 回调"
echo "  - Consumer 异步确认"
echo "  - 错误处理和 Return 回调"
echo ""
echo "也可以访问 RabbitMQ 管理界面查看队列状态："
echo "  http://localhost:15672"
echo "  用户名: guest"
echo "  密码: guest"


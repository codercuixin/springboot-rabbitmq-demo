#!/bin/bash

# 测试消息发送重试功能

BASE_URL="http://localhost:8080/api/messages"

echo "========================================"
echo "  RabbitMQ 消息发送重试功能测试"
echo "========================================"
echo ""

# 1. 测试带重试的发送（推荐使用）
echo "【测试1】发送消息（带自动重试）"
echo "----------------------------------------"
curl -X POST "${BASE_URL}/send-with-retry" \
  -H "Content-Type: application/json" \
  -d '{"content": "测试带重试的消息发送"}' \
  | jq '.'
echo ""
echo ""

# 2. 测试无重试的发送（对比）
echo "【测试2】发送消息（无重试版本）"
echo "----------------------------------------"
curl -X POST "${BASE_URL}/send" \
  -H "Content-Type: application/json" \
  -d '{"content": "测试无重试的消息发送"}' 
echo ""
echo ""

# 3. 查看失败消息列表
echo "【测试3】查看失败消息列表"
echo "----------------------------------------"
curl -X GET "${BASE_URL}/failed" | jq '.'
echo ""
echo ""

echo "========================================"
echo "  测试完成"
echo "========================================"
echo ""
echo "📖 使用说明："
echo ""
echo "1. 推荐使用: /send-with-retry"
echo "   ✅ 自动处理临时网络问题"
echo "   ✅ 重试3次，指数退避（1s、2s、4s）"
echo "   ✅ 失败后自动保存到失败记录"
echo ""
echo "2. 普通版本: /send"
echo "   ⚠️  不会自动重试"
echo "   ⚠️  临时网络问题也会失败"
echo ""
echo "3. 模拟网络故障测试："
echo "   - 停止 RabbitMQ: docker-compose stop rabbitmq"
echo "   - 发送消息: curl -X POST ${BASE_URL}/send-with-retry ..."
echo "   - 观察重试日志"
echo "   - 启动 RabbitMQ: docker-compose start rabbitmq"
echo ""


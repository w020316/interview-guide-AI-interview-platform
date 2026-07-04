#!/bin/bash
# AI 智能面试辅助平台 - 一键启动脚本（Linux/macOS）
# 用法：./start-dev.sh

set -e

echo "========================================"
echo "  AI 智能面试辅助平台 本地启动"
echo "========================================"

# 检查环境
echo -e "\n[1/5] 检查环境..."

command -v java >/dev/null 2>&1 || { echo "  ✗ Java 未安装"; exit 1; }
echo "  ✓ Java 已安装"

command -v mvn >/dev/null 2>&1 || { echo "  ✗ Maven 未安装"; exit 1; }
echo "  ✓ Maven 已安装"

command -v node >/dev/null 2>&1 || { echo "  ✗ Node.js 未安装"; exit 1; }
echo "  ✓ Node.js 已安装"

# 检查 API Key
if [ -z "$DASHSCOPE_API_KEY" ]; then
    echo -e "\n[!] 未检测到 DASHSCOPE_API_KEY 环境变量"
    echo "    获取地址：https://bailian.console.aliyun.com/"
    read -p "    请输入 API Key（直接回车跳过）: " apiKey
    [ -n "$apiKey" ] && export DASHSCOPE_API_KEY=$apiKey
fi

# 检查 PostgreSQL
echo -e "\n[2/5] 检查 PostgreSQL..."
if nc -z localhost 5432 2>/dev/null; then
    echo "  ✓ PostgreSQL 已运行"
else
    echo "  ✗ PostgreSQL 未运行"
    echo "    A. Docker: docker-compose up -d postgres redis"
    echo "    B. 本地安装 PostgreSQL 16"
    echo "    C. 用云端 Supabase（设置 DATABASE_URL）"
    read -p "    选择 (A/B/C)，回车跳过: " choice
    [ "$choice" = "A" ] && docker-compose up -d postgres redis && sleep 5
fi

# 安装前端依赖
echo -e "\n[3/5] 安装前端依赖..."
cd frontend
[ -d node_modules ] || npm install
echo "  ✓ 前端依赖就绪"
cd ..

# 编译后端
echo -e "\n[4/5] 编译后端..."
cd backend
mvn clean compile -q
echo "  ✓ 后端编译成功"
cd ..

# 启动服务
echo -e "\n[5/5] 启动服务..."

echo "  启动后端..."
cd backend
mvn spring-boot:run &
BACKEND_PID=$!
cd ..

sleep 3

echo "  启动前端..."
cd frontend
npm run dev &
FRONTEND_PID=$!
cd ..

echo -e "\n========================================"
echo "  启动完成！"
echo "========================================"
echo "  前端：http://localhost:3000"
echo "  后端：http://localhost:8080"
echo "  健康检查：http://localhost:8080/actuator/health"
echo -e "\n  按 Ctrl+C 退出"

trap "kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit" SIGINT SIGTERM
wait

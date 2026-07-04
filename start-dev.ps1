# AI 智能面试辅助平台 - 一键启动脚本
# 用法：.\start-dev.ps1

$ErrorActionPreference = "Stop"
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AI 智能面试辅助平台 本地启动" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 检查环境
Write-Host "`n[1/5] 检查环境..." -ForegroundColor Yellow

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "  ✗ Java 未安装，请先安装 JDK 21" -ForegroundColor Red
    exit 1
}
Write-Host "  ✓ Java 已安装" -ForegroundColor Green

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "  ✗ Maven 未安装" -ForegroundColor Red
    exit 1
}
Write-Host "  ✓ Maven 已安装" -ForegroundColor Green

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "  ✗ Node.js 未安装" -ForegroundColor Red
    exit 1
}
Write-Host "  ✓ Node.js 已安装" -ForegroundColor Green

# 检查 API Key
$apiKey = $env:DASHSCOPE_API_KEY
if (-not $apiKey) {
    Write-Host "`n[!] 未检测到 DASHSCOPE_API_KEY 环境变量" -ForegroundColor Red
    Write-Host "    请先设置：`$env:DASHSCOPE_API_KEY='sk-your-key'" -ForegroundColor Yellow
    Write-Host "    获取地址：https://bailian.console.aliyun.com/" -ForegroundColor Yellow
    $apiKey = Read-Host "    或现在输入 API Key（直接回车跳过）"
    if ($apiKey) {
        $env:DASHSCOPE_API_KEY = $apiKey
    }
}

# 检查 PostgreSQL
Write-Host "`n[2/5] 检查 PostgreSQL..." -ForegroundColor Yellow
$pgRunning = Test-NetConnection -ComputerName localhost -Port 5432 -WarningAction SilentlyContinue
if ($pgRunning.TcpTestSucceeded) {
    Write-Host "  ✓ PostgreSQL 已运行（localhost:5432）" -ForegroundColor Green
} else {
    Write-Host "  ✗ PostgreSQL 未运行" -ForegroundColor Red
    Write-Host "    请选择启动方式：" -ForegroundColor Yellow
    Write-Host "    A. Docker：docker-compose up -d postgres" -ForegroundColor Yellow
    Write-Host "    B. 本地安装 PostgreSQL 16" -ForegroundColor Yellow
    Write-Host "    C. 用云端 Supabase（设置 DATABASE_URL 环境变量）" -ForegroundColor Yellow
    $choice = Read-Host "    选择 (A/B/C)，回车跳过"
    if ($choice -eq "A" -and (Get-Command docker -ErrorAction SilentlyContinue)) {
        docker-compose up -d postgres redis
        Start-Sleep -Seconds 5
        Write-Host "  ✓ Docker PostgreSQL 已启动" -ForegroundColor Green
    }
}

# 安装前端依赖
Write-Host "`n[3/5] 安装前端依赖..." -ForegroundColor Yellow
Push-Location frontend
if (-not (Test-Path node_modules)) {
    npm install
    Write-Host "  ✓ 前端依赖安装完成" -ForegroundColor Green
} else {
    Write-Host "  ✓ 前端依赖已存在" -ForegroundColor Green
}
Pop-Location

# 编译后端
Write-Host "`n[4/5] 编译后端..." -ForegroundColor Yellow
Push-Location backend
mvn clean compile -q
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ✓ 后端编译成功" -ForegroundColor Green
} else {
    Write-Host "  ✗ 后端编译失败" -ForegroundColor Red
    Pop-Location
    exit 1
}
Pop-Location

# 启动服务
Write-Host "`n[5/5] 启动服务..." -ForegroundColor Yellow

# 启动后端（新窗口）
Write-Host "  启动后端（新窗口）..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-Command", "cd backend; mvn spring-boot:run"

Start-Sleep -Seconds 3

# 启动前端（新窗口）
Write-Host "  启动前端（新窗口）..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-Command", "cd frontend; npm run dev"

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "  启动完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "  前端：http://localhost:3000" -ForegroundColor White
Write-Host "  后端：http://localhost:8080" -ForegroundColor White
Write-Host "  健康检查：http://localhost:8080/actuator/health" -ForegroundColor White
Write-Host "`n  按 Ctrl+C 退出脚本（后端前端窗口需手动关闭）" -ForegroundColor Gray

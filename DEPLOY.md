# 部署指南：0 元上云（Vercel + Render + Supabase + Upstash）

> 全程 GitHub 登录，无需信用卡，永久免费
> 已切换为 Render（Koyeb 需要付费），更适合学生

## 部署架构

```
┌────────────────────────────────────────────────┐
│  Vercel（前端 Vue3 静态托管，永久免费）         │
└──────────────────┬─────────────────────────────┘
                   │
┌──────────────────▼─────────────────────────────┐
│  Render（后端 Spring Boot Docker，免费层）      │
│  512MB 内存，15 分钟无访问休眠                  │
│  用 UptimeRobot 保活                           │
└──┬────────────┬───────────────────────────────┘
   │            │
   ▼            ▼
┌──────────┐ ┌──────────────┐
│ Supabase │ │  Upstash     │
│ PostgreSQL│ │  Redis       │
│ +pgvector│ │  免费10K/天  │
│ 500MB免费 │ └──────────────┘
└──────────┘
```

## 各服务免费额度对比

| 服务 | 用途 | 免费额度 | 信用卡 | 休眠 |
|------|------|---------|--------|------|
| **Vercel** | 前端托管 | 100GB 流量/月 | ❌ | 不休眠 |
| **Render** | 后端 Docker | 512MB + 5GB 流量 | ❌ | 15 分钟无访问休眠 |
| **Supabase** | PostgreSQL + pgvector | 500MB + 5GB 流量 | ❌ | 7 天不访问暂停 |
| **Upstash** | Redis | 10K 命令/天 | ❌ | 不休眠 |
| **UptimeRobot** | 保活监控 | 50 个监控 | ❌ | - |

**总成本：0 元/月，全部 GitHub 登录，无需任何信用卡**

---

## 你要做的 8 步（约 30 分钟）

### Step 1: 创建 Supabase 数据库（5 分钟）

1. 访问 https://supabase.com/ → 用 GitHub 登录
2. **New Project**：
   - Name: `interview-guide`
   - Database Password: 自己设置（**记下来！**）
   - Region: **Singapore**
3. 创建完成后，进入 **SQL Editor**
4. 把下面这个链接的 SQL 内容粘贴进去执行：
   - 文件位置：`backend/src/main/resources/schema.sql`
   - 或直接复制粘贴仓库里这个文件的内容
5. 在 **Settings → Database** 找到连接信息：
   - Host: `db.xxxx.supabase.co`
   - Database: `postgres`
   - User: `postgres`
   - Port: `5432`

**记录以下值**（待会 Render 要用）：
```
DATABASE_URL=jdbc:postgresql://db.xxxx.supabase.co:5432/postgres
DATABASE_USER=postgres
DATABASE_PASSWORD=你设置的密码
```

---

### Step 2: 创建 Upstash Redis（2 分钟）

1. 访问 https://upstash.com/ → 用 GitHub 登录
2. **Create Database**：
   - Name: `interview-redis`
   - Region: `Global (Edge)` 或 `us-east-1`
3. 创建后获取连接信息

**记录以下值**：
```
REDIS_HOST=xxxx.upstash.io
REDIS_PORT=6379
REDIS_PASSWORD=你的 token
```

---

### Step 3: 获取通义千问 API Key（2 分钟）

1. 访问 https://bailian.console.aliyun.com/
2. 用支付宝/钉钉登录（送免费额度，学生够用）
3. **API Key 管理** → 创建 API Key
4. 复制 `sk-xxxx`

---

### Step 4: 推送代码到 GitHub（5 分钟）

```bash
cd d:\xm\wz\新建文件夹\interview-guide

# 初始化 Git
git init
git add .
git commit -m "feat: AI 智能面试辅助平台初始化"

# 在 GitHub 网页创建仓库 interview-guide（不要勾选 README）
git branch -M main
git remote add origin https://github.com/你的用户名/interview-guide.git
git push -u origin main
```

---

### Step 5: 部署后端到 Render（10 分钟）

1. 访问 https://dashboard.render.com/ → 用 GitHub 登录
2. 点 **New +** → **Blueprint**
3. 选择你的 `interview-guide` 仓库
4. Render 会自动读取 `render.yaml`，识别出 `interview-guide-backend` 服务
5. **填写环境变量**（sync: false 的那些）：

   | Key | Value |
   |-----|-------|
   | `DASHSCOPE_API_KEY` | `sk-你的通义千问key` |
   | `DATABASE_URL` | `jdbc:postgresql://db.xxxx.supabase.co:5432/postgres` |
   | `DATABASE_PASSWORD` | 你的 Supabase 密码 |
   | `REDIS_HOST` | `xxxx.upstash.io` |
   | `REDIS_PASSWORD` | 你的 Upstash token |

6. 点 **Apply**
7. 等待构建（约 5-8 分钟，首次会下载 Maven 依赖）
8. 构建完成后，获取访问地址：
   - 格式：`https://interview-guide-backend.onrender.com`
9. **验证**：访问 `https://interview-guide-backend.onrender.com/actuator/health`
   - 应返回：`{"status":"UP"}`

---

### Step 6: 部署前端到 Vercel（3 分钟）

1. 访问 https://vercel.com/ → 用 GitHub 登录
2. **New Project** → **Import Git Repository**
3. 选择 `interview-guide` 仓库
4. 配置：
   - **Root Directory**: 点开，选 `frontend` 文件夹
   - **Framework Preset**: Vue
   - **Build Command**: `npm install && npm run build`
   - **Output Directory**: `dist`
   - **Environment Variables**：
     - Key: `VITE_API_BASE`
     - Value: `https://interview-guide-backend.onrender.com`
5. 点 **Deploy**
6. 等 1-2 分钟，获取访问地址：
   - 格式：`https://interview-guide-你的名.vercel.app`

---

### Step 7: 配置保活（2 分钟，防止 Render 休眠）

Render 免费层 15 分钟无访问会休眠，用 UptimeRobot 每 5 分钟 ping 一次：

1. 访问 https://uptimerobot.com/ → 注册
2. **Add Monitor**：
   - Monitor Type: `HTTP(s)`
   - Friendly Name: `interview-guide-backend`
   - URL: `https://interview-guide-backend.onrender.com/actuator/health`
   - Monitoring Interval: `5 minutes`
3. 保存

**可选**：再加一个监控前端地址，确保整套服务可用。

---

### Step 8: 验证部署

| 项目 | 访问地址 | 预期结果 |
|------|---------|---------|
| 前端 | https://interview-guide-你的名.vercel.app | 看到 AI 面试平台界面 |
| 后端健康检查 | https://interview-guide-backend.onrender.com/actuator/health | `{"status":"UP"}` |
| API 信息 | https://interview-guide-backend.onrender.com/api/info | 返回 JSON |
| 简历分析 | 前端 → 简历分析 Tab → 输入简历 → 点分析 | AI 返回评分 |

---

## 部署完成

**总成本：0 元/月**

| 项目 | 访问地址 |
|------|---------|
| 前端 | https://interview-guide-你的名.vercel.app |
| 后端 API | https://interview-guide-backend.onrender.com |
| 健康检查 | https://interview-guide-backend.onrender.com/actuator/health |

**简历呈现话术**：
> 项目采用 Serverless 全托管架构，前端 Vercel + 后端 Render + 数据库 Supabase + Redis Upstash，零运维成本，自动 CI/CD，后端 Docker 容器化在 512MB 内存下稳定运行。

---

## 常见问题

### Q1：Render 构建失败 "out of memory"

Render 免费层构建时内存有限，如果遇到 OOM：
- 检查 Dockerfile 是否多阶段构建（已是）
- 在 Render Dashboard → Settings → 调整 Build Memory（免费层可临时提升到 1024MB）

### Q2：Render 启动后健康检查失败

1. 查看 Render → Logs 标签页，看启动日志
2. 常见原因：
   - `DATABASE_URL` 格式不对（应为 `jdbc:postgresql://...`）
   - `DASHSCOPE_API_KEY` 没配
   - `REDIS_PASSWORD` 错误
3. 修复环境变量后，Render 会自动重新部署

### Q3：Render 服务休眠了首次访问慢

Render 免费层 15 分钟无访问会休眠，首次唤醒需 30-50 秒。
- 解决：UptimeRobot 每 5 分钟 ping 一次（Step 7 已配置）
- 备选：升级 Render 付费层（$7/月）不休眠

### Q4：Supabase 数据库暂停了

Supabase 免费层 7 天不访问会暂停。
- 解决：登录 Supabase Dashboard 点 Resume
- 预防：UptimeRobot 也加一个监控 Supabase 的地址

### Q5：Vercel 前端访问后端跨域

后端已配置 `@CrossOrigin(origins = "*")`，应该不会跨域。
如有问题，检查浏览器控制台错误信息。

### Q6：AI 接口报 401

`DASHSCOPE_API_KEY` 没配或配错，去阿里云百炼重新复制。

### Q7：通义千问 API 限流

免费额度有限，高并发会限流。Redis 缓存已配置，重复请求会走缓存。

---

## 升级方案（流量大了再考虑）

| 阶段 | 月成本 | 方案 | 适合场景 |
|------|--------|------|---------|
| **学生 Demo** | **0 元** | 当前方案 | 简历项目、面试演示 |
| 小流量 | 10 元 | 阿里云学生机 2C4G | 50+ 用户日常使用 |
| 正式上线 | 50 元 | 阿里云 ECS + RDS | 生产环境 |

---

## 各平台官方文档

- Render 文档：https://render.com/docs
- Vercel 文档：https://vercel.com/docs
- Supabase 文档：https://supabase.com/docs
- Upstash 文档：https://docs.upstash.com/redis
- 通义千问：https://help.aliyun.com/zh/dashscope/

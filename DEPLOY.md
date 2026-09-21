# 部署指南：0 元上云（Cloudflare Pages + Render + Supabase + Upstash）

> 全程 GitHub 登录，无需信用卡，永久免费
> 已切换为 Render（Koyeb 需要付费），更适合学生

## 部署架构

```
┌────────────────────────────────────────────────┐
│  Cloudflare Pages（前端 Vue3 静态托管，免费）   │
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
| **Cloudflare Pages** | 前端托管 | 无限流量/500 次构建/月 | ❌ | 不休眠 |
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
5. 在 **Settings → Database** 找到连接信息，并**改用 Connection Pooling 地址**：
   - 位置：Settings → Database → Connection string → **Connection pooling**
   - 格式：`aws-0-xxx.pooler.supabase.com:5432`（**不是** `db.xxxx.supabase.co`）

**记录以下值**（待会 Render 要用）——
注意 `application-prod.yml` **只读 `DATABASE_URL` 这一个变量**，
用户名密码必须写进 URL 里，不接受分开的 `DATABASE_USER` / `DATABASE_PASSWORD`：

```
DATABASE_URL=jdbc:postgresql://aws-0-xxx.pooler.supabase.com:5432/postgres?user=postgres.你的项目REF&password=你设置的密码
```

---

### Step 2: 创建 Upstash Redis（2 分钟）

1. 访问 https://upstash.com/ → 用 GitHub 登录
2. **Create Database**：
   - Name: `interview-redis`
   - Region: `Global (Edge)` 或 `us-east-1`
3. 创建后在 **Details** 页找到 `REDIS_URL`（形如 `rediss://default:xxxx@xxxx.upstash.io:6379`）

**记录以下值**（同样只用一个变量，`application-prod.yml` 只读 `REDIS_URL`）：
```
REDIS_URL=rediss://default:你的token@xxxx.upstash.io:6379
```

> 注：`rediss://` 是 TLS 连接（两个 s）。`application-prod.yml` 已固定
> `spring.data.redis.ssl.enabled: true`，且 Redis 仅用于 AI 响应缓存，
> 失效不影响登录/鉴权（`RedisConfig` 已做容错回退）。

---

### Step 3: 获取 AI 密钥（对话 + Embedding，5 分钟）

> ⚠️ **2026-09-20 重要修正**：原方案只配了 Agnes 一个 Key，但
> **Agnes 没有任何 embedding 通道**（调用返回 `503 model_not_found`）。
> 这直接导致线上 RAG 知识库**长期空转** —— 实测线上 `/api/knowledge/ask`
> 会返回「⚠️ 参考资料不足，以下回答基于通用知识」。
> 因此 embedding 必须单独配一个能用的提供方，见 3.2。

**3.1 对话模型（必配 + 建议配容灾）**

| 用途 | 平台 | 拿 Key 的位置 |
|---|---|---|
| 主力 | Agnes AI | https://platform.agnes-ai.com/settings/apikeys |
| 兜底（建议） | 智谱 AI | https://open.bigmodel.cn/ → API Keys |

Agnes 兼容 OpenAI 协议，免费且不限量，作为主力足矣；
智谱作为跨厂商容灾（Agnes 不可用时自动降级）。

**3.2 Embedding（必配，否则知识库是空的）**

推荐 **阿里云百炼 `text-embedding-v4`**：免费 100 万 Token、中文效果第一梯队、
OpenAI 兼容。

1. 访问 https://bailian.console.aliyun.com/ → 支付宝/淘宝实名认证
2. 开通「百炼大模型服务」（免费额度自动发放，无需领取）
3. 进入 **模型广场** → 找到 `text-embedding-v4` → 创建 API Key
4. 复制 `sk-xxxx`

> ⚠️ **Key 与地域强绑定**：北京地域创建的 Key 不能用于其他地域端点。
> ⚠️ 建议在控制台开启「**用完即停**」，避免额度耗尽后自动转按量付费。

**3.3 记录以下值**（待会 Render 要用）

```
AI_API_KEY=sk-你的Agnes key
AI_ZHIPU_API_KEY=你的智谱 key（可选，作为兜底）
AI_EMBEDDING_API_KEY=sk-你的百炼 key
```

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
5. **填写环境变量**（即 `render.yaml` 里标了 `sync: false` 的那些）：

   | Key | Value |
   |-----|-------|
   | `AI_API_KEY` | `sk-你的Agnes key` |
   | `AI_ZHIPU_API_KEY` | 你的智谱 key（兜底，可留空） |
   | `AI_EMBEDDING_API_KEY` | `sk-你的百炼 key` ← **不填知识库就是空的** |
   | `DATABASE_URL` | 见下方格式，**用户名密码要写进 URL** |
   | `REDIS_URL` | `rediss://default:你的token@xxxx.upstash.io:6379` |
   | `JWT_SECRET` | 随机长字符串（≥32 字符） |
   | `SEED_ADMIN_PASSWORD` | 种子管理员密码 |
   | `SUPABASE_URL` | `https://xxxx.supabase.co` |
   | `SUPABASE_SERVICE_KEY` | Supabase service_role key |

   **`DATABASE_URL` 必须带凭据**（`application-prod.yml` 只读这一个变量，
   不接受单独的 `DATABASE_USER` / `DATABASE_PASSWORD`）：
   ```
   jdbc:postgresql://aws-0-xxx.pooler.supabase.com:5432/postgres?user=postgres.你的项目REF&password=你的密码
   ```
   > 建议用 Supabase 的 **Connection Pooling** 地址（`...pooler.supabase.com`），
   > 免费层直连数有限，Render 冷启动时容易撞连接上限。

   **注意**：模型名、base-url、embedding 维度等已在 `render.yaml` 中配好，
   无需手动填写。其中 `AI_EMBEDDING_DIMENSIONS` **必须与 embedding 模型的默认输出维度一致**
   （百炼 `text-embedding-v4` = 1024；代码不会把该值传给 API，仅用于建表）。

6. **清空旧的向量表**（首次切换到新 embedding 供应商时必做）
   - 进入 Supabase → **SQL Editor** → 执行：
     ```sql
     DROP TABLE IF EXISTS vector_store;
     ```
   - 原因：不同 embedding 模型的向量空间**不通用**，
     且旧表维度可能与新模型不符（会导致导入报维度不匹配）。
   - 重启后 Spring AI 会按 `initialize-schema: true` 自动按新维度重建，
     启动时再自动播种全行业预置知识。

7. 点 **Apply**
8. 等待构建（约 5-8 分钟，首次会下载 Maven 依赖）
9. 构建完成后，获取访问地址：
   - 格式：`https://interview-guide-backend.onrender.com`
10. **验证**：访问 `https://interview-guide-backend.onrender.com/actuator/health`
    - 应返回：`{"status":"UP"}`

---

### Step 6: 部署前端到 Cloudflare Pages（3 分钟）

> ⚠️ **2026-09-21 修正**：本节原为 Vercel 步骤。实际线上前端托管在 **Cloudflare Pages**
> （`interview-guide-ai-interview-platform.pages.dev`），Vercel 域名已停用，
> 仓库中的 `vercel.json` 已删除；安全响应头改由 `frontend/public/_headers` 承担。

1. 访问 https://dash.cloudflare.com/ → 用 GitHub 登录
2. **Workers & Pages** → **Create** → **Pages** → **Connect to Git**
3. 选择 `interview-guide` 仓库
4. 配置构建：
   - **Production branch**: `main`
   - **Framework preset**: `Vue`
   - **Build command**: `npm run build`
   - **Build output directory**: `dist`
   - **Root directory**: `frontend`（**必填**，仓库根不是前端工程）
5. **Environment Variables**（注意变量名必须与代码一致，写错则前端拿不到后端地址）：
   - Key: `VITE_API_BASE_URL`
   - Value: `https://interview-guide-backend.onrender.com`
6. 点 **Save and Deploy**，等 1-2 分钟，获取访问地址：
   - 格式：`https://<项目名>.pages.dev`
7. **确认安全响应头已生效**（`frontend/public/_headers` 会被自动应用）：
   ```bash
   curl -sSI https://<项目名>.pages.dev/ | grep -i x-frame-options
   # 期望输出：x-frame-options: DENY
   ```
   > 若缺失，检查 `_headers` 中**注释是否被写在某个路径区块内部**——
   > Cloudflare 会静默忽略整块（2026-09-21 实测踩坑，注释必须放在路径行之前）。

---

### Step 7: 保活（**已内置，无需操作**）

Render 免费层**15 分钟无请求即休眠**，下次访问要等约 1 分钟冷启动。
本项目已在仓库内内置保活，**推送代码后自动生效，不需要注册任何外部服务**：

- 文件：`.github/workflows/keepalive.yml`
- 频率：**每 5 分钟** ping 一次（GitHub Actions cron 的最小粒度）
- 行为：ping `/api/info` 唤醒实例（8 分钟耐心重试循环，容忍 JVM 冷启动）；
  再校验 `/api/health` 返回 `status: UP`，异常则 Run 失败并邮件告警
- 成本：本仓库为 **public**，GitHub Actions 分钟数不限量

> ⚠️ **注意**：GitHub 对「60 天无提交活动」的仓库会**自动停用**定时工作流。
> 项目持续开发时不受影响；若长期停更，需到仓库 Actions 页手动重新启用。
>
> ⚠️ **若改为 private 仓库**：每 5 分钟一次 ≈ 288 次/天，按每次最少 1 分钟计费，
> 会超出 2000 分钟/月的免费额度。此时应改用下面的外部监控，或把间隔调回 10 分钟。

**可选：外部监控做冗余**（如需独立于 GitHub 的第二条探活、或要 5 分钟级告警）

1. 访问 https://uptimerobot.com/ → 注册（免费 50 个监视器，无需信用卡）
2. **Add Monitor**：
   - Monitor Type: `HTTP(s)`
   - Friendly Name: `interview-guide-backend`
   - URL: `https://interview-guide-backend.onrender.com/api/health`
   - Monitoring Interval: `5 minutes`
3. 保存；可再加一个监控前端 `https://interview-guide-ai-interview-platform.pages.dev`

**前端侧兜底**：`frontend/src/utils/backendWake.ts` 已实现冷启动唤醒器
（挂载前预热 + 登录页二次确认 + 「正在冷启动，已等待 Ns」实时进度），
即使保活偶发失效，用户也不会遇到静默等待或不可读的英文报错。

---

### Step 8: 验证部署

| 项目 | 访问地址 | 预期结果 |
|------|---------|---------|
| 前端 | https://interview-guide-ai-interview-platform.pages.dev | 看到 AI 面试平台界面 |
| 后端健康检查 | https://interview-guide-backend.onrender.com/actuator/health | `{"status":"UP"}` |
| API 信息 | https://interview-guide-backend.onrender.com/api/info | 返回 JSON |
| 简历分析 | 前端 → 简历分析 Tab → 输入简历 → 点分析 | AI 返回评分 |

---

## 部署完成

**总成本：0 元/月**

| 项目 | 访问地址 |
|------|---------|
| 前端 | https://interview-guide-ai-interview-platform.pages.dev |
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

`AI_API_KEY` 没配或配错，去 https://platform.agnes-ai.com/settings/apikeys 重新复制。

### Q7：Agnes AI 接口调用失败

Agnes AI 免费且不限量，但需确认：
- `AI_BASE_URL` = `https://apihub.agnes-ai.com/v1`
- `AI_MODEL` = `agnes-base`
- `AI_API_KEY` 以 `sk-` 开头

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
- Cloudflare Pages 文档：https://developers.cloudflare.com/pages/
- Supabase 文档：https://supabase.com/docs
- Upstash 文档：https://docs.upstash.com/redis
- Agnes AI：https://platform.agnes-ai.com/
- 通义千问（备选）：https://help.aliyun.com/zh/dashscope/

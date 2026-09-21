# AI 智能面试辅助平台 · 开发文档

> 版本：1.32.0 ｜ 日期：2026-09-13
> 仓库：https://github.com/w020316/interview-guide-AI-interview-platform
> 在线：前端 https://interview-guide-ai-interview-platform.pages.dev ｜ 后端 https://interview-guide-backend.onrender.com

一份面向开发者/维护者的权威开发文档，涵盖技术栈、系统架构、模块划分、API 一览、
关键技术特性、安全与可用性保障、部署与测试流程。随版本演进持续更新。

---

## 一、技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 + TypeScript + Vite（v8，rolldown 引擎）+ Element Plus + Pinia/Vue-Router |
| 后端 | Java 21 + Spring Boot 3.3 + Spring AI 1.0 |
| 数据库 | PostgreSQL（Supabase 托管，Hikari 连接池） |
| 向量存储 | pgvector（知识库 RAG） |
| 缓存/限流 | Redis（Upstash，TLS），bucket4j 内存限流 |
| AI 模型 | B.AI GLM-5.3-Flash（主）/ Qwen3.8-Flash（次）/ Agnes（兜底），Embedding 走 text-embedding-3-small |
| 部署 | 前端 Cloudflare Pages / Vercel；后端 Render（免费层，新加坡）；GitHub Actions CI |
| 测试 | 后端 JUnit 337 例；前端 Vitest（覆盖率阈值 80%）+ 并发压测脚本 |

---

## 二、系统架构

```
┌──────────────────────────────────────────────────────────┐
│                   浏览器（前端 Vue3 SPA）                    │
│   设计系统 v4 × 暗色模式 × 响应式 × 按需分包（vite 8/rolldown） │
└───────────────┬───────────────────────────┬──────────────┘
                │ 同源 /api（Pages rewrites）│ 直连跨域（VITE_API_BASE_URL，CORS）
                │ 或 SSE（EventSource）      │
┌───────────────▼───────────────────────────▼──────────────┐
│             Web 网关（Cloudflare Pages / Vercel）           │
└───────────────────────────┬───────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────┐
│              Spring Boot 3.3 后端（Render/本地）             │
│  Controller → Service → Repository / Redis / AI / RAG      │
│  JWT 鉴权 · 限流 · 全局异常 · 指标埋点 · 幂等建表 · 优雅停机    │
└──────┬──────────────┬──────────────┬──────────────┬─────────┘
       │              │              │              │
┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼──────────┐
│ PostgreSQL │ │ pgvector   │ │   Redis     │ │  LLM 多厂商降级链 │
│ 业务数据    │ │ 知识库向量  │ │ 缓存/黑名单  │ │ GLM→Qwen→Agnes  │
└────────────┘ └────────────┘ └────────────┘ └─────────────────┘
```

### 分层与模块（后端 `com.example.interview`）
- `controller`：REST / SSE 接口，统一 `Result<T>`（`{ code, message, data, timestamp }`）
- `service`：业务逻辑，含子包 `agent`（智能体）、`job`（招聘/岗位匹配/分类）、`document`、`rag` 等
- `service.agent`：`AgentService`（ReAct 循环 + 工具编排）、`AgentTools`（联网搜岗/简历匹配/出题/追问等工具注册表）
- `service.job`：`JobAgentService`、`JobClassifyService`（AI+规则分类）、`JobPlatformAdapter`、`Seed*Provider`（多数据源）、`JobMatchService`（简历-岗位匹配）、`JobRefreshScheduler`（定时刷新）
- `repository`：Spring Data JPA
- `security`：JWT 过滤 + `JwtUtil`（subject=userId、含 issuer/audience/ROLE_ADMIN）
- `config`：Security（CORS 白名单/法限制）、AiConfig（降级链）、RedisConfig（容错）、MetricsConfig、SchemaInitializer（幂等建表）、AdminSeedInitializer（种子管理员）
- `interceptor`：`RateLimitInterceptor`（IP 级限流）
- `ai`：`AiConcurrencyGuard`（全局 5 许可并发闸门）、`FallbackChatModel`（多模型降级）
- `util`：JsonRepairUtil、PromptSanitizer、SsrUrlValidator、ClientIpUtil、FileType 校验
- `common`：`Result`、`ResultCode`、`GlobalExceptionHandler`、`BusinessException`

---

## 三、功能模块与前端页面

| 模块 | 路由 | 说明 |
|------|------|------|
| 首页 | `/` | 平台介绍/入口 |
| 登录注册 | `/login` | 账号密码 + JWT |
| 简历分析 | `/resume`、`/resume/history` | 文本/文件/URL 导入、AI 分析、历史 |
| 岗位分析 | `/job` | JD 解析 / 能力差距 / 求职信生成 |
| 招聘广场 | `/jobs` | 岗位列表、筛选、简历匹配推荐、收藏 |
| 智能体 | `/agent` | 对话式多工具智能体（联网搜岗/推荐/出题/追问） |
| 模拟面试 | `/interview` | 出题、评估、多轮追问、SSE 流式对话 |
| 知识库 | `/knowledge` | RAG 文档导入、问答、错题本 |
| 收藏 | `/favorites` | 从收藏发起面试、手动加题 |
| 日历 | `/calendar` | 面试安排 |
| 错题本 | `/wrong-book` | 薄弱点 |
| 进度/趋势 | `/progress` | 成绩趋势 |
| 个人中心 | `/profile` | 资料/改名等 |
| 管理后台 | `/admin` | 仅 ROLE_ADMIN：总览/岗位/用户/指标 |

---

## 四、API 一览（前缀 `/api`）

> 鉴权：`Authorization: Bearer <JWT>`；公开端点：`/auth/**`、`/info`、`/health`、actuator、swagger。

### 认证 `auth`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /auth/register | 注册（实时校验） |
| POST | /auth/login | 登录，签发 JWT（含 ROLE_ADMIN） |
| POST | /auth/logout | 登出（吊销令牌） |
| GET | /auth/me | 当前用户信息 |

### 简历 `resume`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /resume/analyze | 简历内容 AI 分析 |
| POST | /resume/upload | 上传解析（PDF/TXT/MD/HTML ≤10MB） |
| POST | /resume/optimize | 简历优化 |
| POST | /resume/import-url | URL 导入（SSRF 防护） |
| GET | /resume/history | 简历历史 |
| GET | /resume/{id} | 简历详情（IDOR→403） |

### 岗位分析 `job`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /job/analyze | JD 解析 |
| POST | /job/gap | 能力差距诊断 |
| POST | /job/letter | 求职信/内推信生成 |

### 招聘广场 `jobs`（JobAgentController）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /jobs | 岗位列表（分页/筛选） |
| GET | /jobs/{id} | 岗位详情 |
| GET | /jobs/meta | 元数据（筛选项） |
| POST | /jobs/match | 简历-岗位匹配推荐 |
| POST | /jobs/refresh | 手动刷新岗位数据 |
| GET | /jobs/favorite、/jobs/favorite/ids | 岗位收藏列表/ID 集 |
| POST | /jobs/favorite/toggle | 收藏/取消 |

### 智能体 `agent`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /agent/chat/stream | SSE 流式多工具对话 |
| GET | /agent/conversations | 会话历史 |
| GET | /agent/conversations/{id}/messages | 会话消息 |

### 模拟面试 `interview` / `session`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /interview/questions | 生成面试题 |
| POST | /interview/evaluate | 答案评估 |
| POST | /interview/followup | 针对性追问 |
| POST | /interview/upload-image | 作答附图上传（userId 取自 JWT） |
| POST | /interview/ask/stream | SSE 流式对话 |
| POST | /session/create | 创建会话 |
| GET | /session/list | 会话历史 |
| GET | /session/{sessionId} | 会话详情 |
| GET/POST | /session/{sessionId}/questions | 题目查询/持久化 |
| POST | /session/answer | 保存作答 |

### 知识库 `knowledge`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /knowledge/search | 知识检索 |
| POST | /knowledge/ask | RAG 问答 |
| POST | /knowledge/import、/import/batch | 文档导入 |
| GET | /knowledge/wrong-questions | 错题本 |
| GET | /knowledge/question-summary、/recent-questions | 统计/最近题目 |

### 收藏 `favorite`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /favorite/list、/favorite/ids | 收藏列表/ID 集（快照式，IDOR 校验） |
| POST | /favorite/toggle | 收藏/取消 |
| POST | /favorite/bank/start | 从收藏发起面试 |
| POST | /favorite/add | 手动加题 |

### 日历 `calendar/event`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /calendar/event/list | 我的日程 |
| POST | /calendar/event | 新增日程 |
| PUT/DELETE | /calendar/event/{id} | 编辑/删除（越权→400） |

### 统计 `stats`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /stats/dashboard | 仪表盘统计 |
| GET | /stats/trend | 成绩趋势 |

### 管理后台 `admin`（仅 ROLE_ADMIN）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /admin/overview | 数据总览 |
| POST | /admin/jobs/refresh | 刷新岗位（管理员不限流） |
| GET | /admin/jobs | 岗位分页管理 |
| POST | /admin/jobs/{id}/deactivate、/activate | 下架/恢复岗位 |
| DELETE | /admin/jobs/{id} | 删除岗位 |
| GET | /admin/users | 用户管理 |
| POST | /admin/users/{id}/ban、/unban | 禁用/解禁 |
| GET | /admin/metrics | AI 调用/缓存/SSE/JVM 指标 |

### 健康 `health`（公开）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /info | 心跳（keepalive 探测防休眠） |
| GET | /health | 深度体检：database/redis/jvm/uptime（v1.32.0 新增） |

---

## 五、AI 模型多厂商降级链

`AiConfig` 构建 `@Primary ChatModel`（`FallbackChatModel`）：

| 顺位 | 供应商 | 模型 | 用途 |
|------|--------|------|------|
| 1 | B.AI | glm-5.3-flash | 主模型（免费额度） |
| 2 | B.AI | qwen3.8-flash | 次模型（免费额度） |
| 3 | Agnes | agnes-2.5-flash | 兜底 |

- **Embedding**（RAG 向量化）：Agnes `text-embedding-3-small`。
- **并发保护**：所有同步 AI 调用经 `AiConcurrencyGuard` 全局 5 许可，避免免费模型限流下并发放大。
- **流式**：`/agent/chat/stream`、`/interview/ask/stream` 走 SSE，20 并发信号量保护。
- 任一供应商失败自动降级到下一顺位；全部失败返回兜底本地数据。

---

## 六、关键技术特性

- **数据库**：prod 关闭 `ddl-auto`，`SchemaInitializer` 启动幂等建表（新表无需人工迁库）。
- **RAG**：pgvector 向量库，检索失败自动降级不影响主流程。
- **AI 容错与降级**：`JsonRepairUtil` 修复模型 JSON；提示词消毒（注入清洗 + 2000 字符截断）；最大 8 步多工具推理；会话标题自动提炼。
- **智能体**：ReAct 循环，工具含 `searchJobs`（联网搜岗）、`matchResumeJobs`（简历匹配）、`generateInterviewQuestions`（出题）、`deepFollowUp`（追问）。
- **招聘数据**：多数据源（内置精选 + 平台适配器 + 热招），每小时定时刷新，AI + 规则双重分类打标。
- **统一异常**：`GlobalExceptionHandler`，`BusinessException` 面向用户返回可重试文案，内部异常不透出细节。
- **可观测**：`/api/health` 深度体检 + Micrometer 指标 + 管理后台实时指标。

---

## 七、安全设计

- **认证**：无状态 JWT（subject=userId、含 issuer/audience、`ROLE_ADMIN` claim）；旧 token 默认 ROLE_USER。
- **管理后台**：`/api/admin/**` 经 `@PreAuthorize` 仅 `ROLE_ADMIN`；管理员名单 = 配置 `app.admin-usernames` / `APP_ADMIN_USERNAMES`；种子管理员 `AdminSeedInitializer` 启动创建。
- **SSRF 防护**：`SsrUrlValidator`（协议/端口/内网/回环/云元数据/DNS 校验）应用于 URL 导入与多模态附图。
- **越权防护**：资源按当前登录用户（`SecurityUtil.getCurrentUserId`）隔离。
- **提示词注入清洗**：`PromptSanitizer` 去除注入模式并截断。
- **限流**：IP 10/min（bucket4j 滑动窗口+过期清理）+ 用户级 30/min（AI 出题/评估等）；探活端点 `/health`、`/info` 排除限流。
- **前端响应头**：HSTS、nosniff、X-Frame-Options: DENY、Permissions-Policy、API `no-store`。
- **依赖安全**：Dependabot 每周扫描（npm/maven/actions）；CI `npm audit --audit-level=high` 阻断高危；当前 audit 0 漏洞。

---

## 八、部署（GitHub Actions + Render + Cloudflare Pages）

- 推送 `main` → CI（后端 Maven 单测 + 前端 vue-tsc/coverage/build/audit）→ Render 自动部署后端 Docker → Cloudflare Pages 自动部署前端。
- 后端 `render.yaml`（Docker，新加坡免费层）：`SPRING_PROFILES_ACTIVE=prod`，密钥走环境变量（JWT_SECRET、AI API Keys、DATABASE_URL、REDIS_URL=rediss://、SEED_ADMIN_USERNAME/PASSWORD、APP_ADMIN_USERNAMES）。
- 前端跨域直连：设置 `VITE_API_BASE_URL`；后端 CORS 白名单仅放行 Cloudflare Pages 域名与本地调试端口（v1.34.1 移除了已停用的 vercel.app）。
- 编译启动：后端 `mvn spring-boot:run -Dspring-boot.run.profiles=local`（H2 + SimpleVectorStore 零依赖联调）；前端 `npm run dev`。

---

## 九、测试与可用性保障

| 层级 | 手段 | 覆盖 |
|------|------|------|
| 单元 | JUnit 337 例 + Vitest（≥80%） | 控制器/服务/安全/限流/异常/工具类 |
| 集成 | `@WebMvcTest` 切片 + `@SpringBootTest` 冒烟（`ApplicationContextSmokeTest`） | 装配正确性、鉴权链、健康接口 |
| 压测 | `node scripts/loadtest.mjs` | 并发下成功率、P50/P95/P99、状态码 |
| 监控 | keepalive 每 10min 探活 + 深度体检（database/redis） | 可用性 + 告警 |
| 应急预案 | `docs/uptime-plan.md` | P0-P2 故障处置 |

完整保障方案见 [docs/uptime-plan.md](uptime-plan.md)。

---

## 十、版本记录

- **v1.32.0**：网站可用性保障方案落地（深度体检 /health、优雅停机、Dependabot、npm audit、依赖升级清洞、压测脚本、uptime-plan 文档）。
- **v1.31.4**：管理员身份体系（ROLE_ADMIN + 种子账号 + 管理后台页面）、退出登录修复、全项目代码审查。
- **v1.31.3**：AI 模块全面检查、登录失败计数清理、全局并发闸门统一。
- **v1.31.2 / 1.30**：智能体能力释放（多工具、联网搜岗、简历匹配、出题、深挖追问、会话标题）。
- 完整更新日志见 [frontend/src/changelog.ts](../frontend/src/changelog.ts)（前端弹窗展示）。
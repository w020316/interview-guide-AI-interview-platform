# AI 智能面试辅助平台（interview-guide）开发文档

> 版本：v1.33.0 ｜ 更新日期：2026-09-19 ｜ 维护者：项目所有者（GitHub @w020316）
>
> 本文档面向后续接手开发的工程师，覆盖环境搭建、目录结构、技术选型、模块实现、代码与提交规范、测试流程、三套环境部署流程、常见问题与支持渠道。所有命令均已在 Windows（PowerShell）/ Linux（bash）/ macOS（zsh）下验证或标注差异。

---

## 目录

1. [项目概述与技术选型](#1-项目概述与技术选型)
2. [开发环境搭建指南](#2-开发环境搭建指南)
3. [项目目录结构说明](#3-项目目录结构说明)
4. [核心功能模块实现细节](#4-核心功能模块实现细节)
5. [代码规范与提交规范](#5-代码规范与提交规范)
6. [测试流程](#6-测试流程)
7. [部署环境要求与配置清单](#7-部署环境要求与配置清单)
8. [部署步骤（本地 / 测试 / 生产）](#8-部署步骤)
9. [常见问题解决方案（FAQ）](#9-常见问题解决方案faq)
10. [技术支持与联系方式](#10-技术支持与联系方式)

---

## 1. 项目概述与技术选型

### 1.1 项目定位

AI 智能面试辅助平台：面向求职者的面试准备工具，提供简历分析、AI 出题、模拟面试评估（含多模态附图）、知识库 RAG 问答、求职智能体（Career Copilot）、岗位聚合与匹配、错题本/学习计划/进度统计等能力。

### 1.2 总体架构

```
┌─────────────────┐   HTTPS/SSE   ┌──────────────────────┐
│  前端 SPA        │ ────────────▶ │  后端 Spring Boot     │
│  Vue3 + Vite    │               │  Java 21 (Docker)     │
│  Vercel/CF Pages │               │  Render 免费层        │
└─────────────────┘               └──────┬───────┬───────┘
                                          │       │
                    ┌─────────────────────┘       └──────────┐
                    ▼                                        ▼
        ┌──────────────────────┐                ┌────────────────────┐
        │ Supabase PostgreSQL  │                │ Upstash Redis(TLS)  │
        │ + pgvector + Storage │                │ (AI 缓存/封禁/限流) │
        └──────────────────────┘                └────────────────────┘
                    ▲                                        ▲
                    │ service key                            │
        ┌───────────┴───────────┐            ┌──────────────┴───────────┐
        │ AI 提供方（OpenAI 协议）│            │  签名 URL / 对象存储      │
        │ Agnes（chat 主力）      │            └──────────────────────────┘
        │ 硅基流动（bge-m3 向量） │
        └───────────────────────┘
```

### 1.3 技术选型与理由

| 层 | 技术 | 版本 | 选型理由 |
|---|---|---|---|
| 语言/运行时 | Java | 21 (LTS) | 虚拟线程、Records、模式匹配；与 Spring Boot 3.3 兼容 |
| 后端框架 | Spring Boot | 3.3.13 | 3.3 线最终 OSS 补丁版本（含内嵌 Tomcat 安全修复）；生态成熟 |
| AI 框架 | Spring AI | 1.0.0 (BOM) | 统一 OpenAI 协议接入多家模型；pgvector 向量库开箱即用 |
| ORM | Spring Data JPA (Hibernate) | 随 Boot | 生产建表由 `schema.sql` + `SchemaInitializer` 幂等维护，`ddl-auto: none` |
| 数据库 | PostgreSQL (Supabase) | 15+ | 免费额度 + pgvector 扩展 + Storage 一体 |
| 本地数据库 | H2 | 随 Boot | `local` profile 零外部依赖联调 |
| 缓存 | Redis (Upstash, TLS) | — | AI 响应缓存/封禁名单/限流；代码层全量降级，不可用不阻断登录 |
| 认证 | JJWT | 0.12.6 | 无状态 JWT（含 issuer/audience/role claim） |
| 限流 | Bucket4j | 8.10.1 | 纯内存令牌桶，不依赖 Redis 扩展包 |
| 向量库 | pgvector (Spring AI starter) | 0.8.2 | 知识库向量持久化，HNSW + COSINE，维度 1024 |
| 向量化 | 硅基流动 BAAI/bge-m3 | 1024 维 | 免费、OpenAI 协议兼容（Agnes 已下线 embedding） |
| 文档解析 | Apache PDFBox / jsoup | 3.0.5 / 1.18.3 | PDF/HTML 简历文本提取，比 Tika 轻量（省 Metaspace） |
| API 文档 | springdoc-openapi | 2.6.0 | 生产环境已关闭（安全） |
| 前端框架 | Vue 3 + TypeScript | 3.4 / 5.4 | 组合式 API；`vue-tsc` 类型检查进 CI |
| 构建工具 | Vite | 8.3 | rolldown 构建快；手动 chunk 拆分（element-plus/markdown/vue-vendor） |
| UI 组件 | Element Plus | 2.7 | 组件齐全、暗色主题支持 |
| 前端测试 | Vitest + @vue/test-utils | 5.0 | 与 Vite 同源配置，v8 覆盖率 |
| 部署 | Render (Docker) + Vercel/Cloudflare Pages | — | 后端免费层 Docker；前端静态托管 + rewrites 代理 |

### 1.4 关键设计决策（务必阅读）

1. **AI 双链路**：Chat 走 `app.ai.chain` 降级链（当前仅 Agnes agnes-2.5-flash；B.AI 节点因 Render→api.b.ai 网络不可达已移除）；Embedding 走独立可插拔配置（硅基流动 bge-m3），两者互不影响。
2. **Embedding 与 pgvector 维度必须一致**：`AI_EMBEDDING_DIMENSIONS`（1024）＝ pgvector `dimensions` ＝ 表列 `vector(1024)`。换 embedding 模型必须同步改维度并重建 `vector_store` 表。
3. **Redis 全量降级**：Redis 只承担 AI 缓存/封禁/限流，登录鉴权不依赖；连接失败自动回退本机地址并降级。
4. **健康检查分级**（P2-08）：匿名 `/api/health` 仅返回 `{"status":"UP"}`；组件级体检在需认证的 `/api/health/detail`。
5. **向量持久化**（P2-17）：生产使用 pgvector（不再用内存实现），向量重启不丢失。
6. **附图签名 URL**（P2-06）：评估前服务端把公开 URL 换签为 10 分钟签名 URL；上传接口附赠 1 小时预览签名 URL；签发失败自动回退公开 URL。

---

## 2. 开发环境搭建指南

### 2.1 必装软件与版本要求

| 软件 | 要求版本 | 说明 |
|---|---|---|
| JDK | **21**（推荐 Temurin 21） | 后端编译与运行；`JAVA_HOME` 必须指向 JDK 21 |
| Maven | **3.9.x**（验证过 3.9.15） | 后端构建；建议配置国内镜像（aliyunmaven）加速 |
| Node.js | **≥ 20，CI 使用 24** | 前端构建与测试；自带 npm ≥ 10 |
| Git | ≥ 2.40 | 版本管理 |
| Docker（可选） | ≥ 24 | 本地容器化验证后端镜像 |
| PostgreSQL / Redis（可选） | 任意 15+/7.x | 仅默认 profile 直连时需要；`local` profile 不需要 |

### 2.2 三平台安装要点

**Windows（PowerShell，推荐开发环境）**

```powershell
# JDK 21：安装 Temurin 21 后确认
java -version   # 应输出 21.x
# Maven：解压到 D:\xm\apache-maven-3.9.x 并加入 PATH
mvn -v
# Node：官网安装 LTS/24
node -v; npm -v
```

⚠️ **Windows 专属注意事项**：
- 项目所在路径**尽量避免中文与空格**。已实测：路径含中文时，Java agent（如 JaCoCo）经 cmd 转发参数会发生编码损坏，导致 forked JVM 启动失败/覆盖率文件丢失。若无法避免，测试命令统一走仓库内 `mvnw` 或改用 8.3 短路径执行。
- PowerShell 不支持 bash 的 heredoc（`<<'EOF'`），写多行 commit message 请使用 `git commit -F <文件>`。
- `curl` 在 PowerShell 是 `Invoke-WebRequest` 的别名，POST JSON 请用 `curl.exe` 并把 JSON 写入临时文件后 `--data "@file"`，避免引号转义地狱。

**macOS**

```bash
brew install openjdk@21 maven node
sudo ln -sfn $(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
java -version && mvn -v && node -v
```

**Linux（Ubuntu 22.04/24.04）**

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk maven
# Node 24（用 nvm）
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash && nvm install 24
java -version && mvn -v && node -v
```

### 2.3 获取代码与首次构建

```bash
git clone https://github.com/w020316/interview-guide-AI-interview-platform.git
cd interview-guide-AI-interview-platform

# 后端（首次会下载依赖，约 2-5 分钟）
cd backend && mvn clean install -DskipTests && cd ..

# 前端
cd frontend && npm ci && cd ..
```

> 建议复制 `frontend` 构建前先复制 `.env.example`（若提供）或直接按 §2.5 设置环境变量。

### 2.4 一键启动脚本

仓库根提供 `start-dev.ps1`（Windows）与 `start-dev.sh`（macOS/Linux），等价于同时启动前后端开发进程；也可按 §8.1 手动分步启动。

### 2.5 本地环境变量速查

| 变量 | 是否必填 | 默认 | 说明 |
|---|---|---|---|
| `AI_API_KEY` | 建议配置 | `sk-placeholder` | Agnes Key；不配也能启动，但 AI 功能运行时报错 |
| `AI_BAI_API_KEY` | 否 | 空 | B.AI 主模型 Key；为空自动跳过该节点 |
| `AI_EMBEDDING_BASE_URL` | RAG 需配置 | 空 | 如 `https://api.siliconflow.cn`（不带 `/v1`，代码会自动剥离误配的 `/v1`） |
| `AI_EMBEDDING_API_KEY` | RAG 需配置 | 空 | 硅基流动 Key |
| `AI_EMBEDDING_MODEL` | RAG 需配置 | `text-embedding-3-small` | 硅基流动用 `BAAI/bge-m3` |
| `AI_EMBEDDING_DIMENSIONS` | RAG 需配置 | `1536` | bge-m3 为 `1024`；必须与 pgvector 列维度一致 |
| `JWT_SECRET` | local 可省 | 内置开发密钥 | 生产必须为强随机 ≥32 字符 |

> `local` profile 使用 H2 内存库 + 内存向量库 + Redis 容错降级，**无需** `DATABASE_URL`/`REDIS_URL`，开箱即跑。

---

## 3. 项目目录结构说明

```
interview-guide/
├── backend/                          # Spring Boot 后端
│   ├── pom.xml                       # Maven 配置（含 jacoco 80% 门槛）
│   ├── Dockerfile                    # 多阶段构建（免费层 512MB 优化）
│   └── src/
│       ├── main/java/com/example/interview/
│       │   ├── InterviewGuideApplication.java
│       │   ├── ai/                   # AI 并发闸门、降级链 FallbackChatModel
│       │   ├── common/               # BusinessException / GlobalExceptionHandler / Result
│       │   ├── config/               # AiConfig(降级链+Embedding)、SecurityConfig、RedisConfig、
│       │   │                         # SchemaInitializer、JsonBodySizeLimitFilter、AiProviderProperties…
│       │   ├── controller/           # REST 控制器（Auth/Interview/Resume/Knowledge/Agent/Jobs/Admin…）
│       │   ├── dto/                  # DashboardStats 等
│       │   ├── entity/               # JPA 实体（User/Resume/InterviewSession/JobPosting…）
│       │   ├── interceptor/          # RateLimitInterceptor（全局限流）
│       │   ├── repository/           # Spring Data JPA 仓库
│       │   ├── security/             # JwtUtil / JwtAuthFilter / RoleUtil
│       │   ├── service/              # 业务服务（Interview/Resume/RagSearch/Admin/SupabaseStorage…）
│       │   │   ├── agent/            # Career Copilot：AgentService(ReAct)/AgentTools
│       │   │   └── job/              # 岗位聚合：JobAgentService/JobClassifyService/HttpJobPlatformAdapter…
│       │   └── util/                 # PromptSanitizer/SsrUrlValidator/PerUserRateLimiter/SseConcurrencyGuard/
│       │                             # JsonRepairUtil/ClientIpUtil/HashUtil/TextUtil
│       └── main/resources/
│           ├── application.yml       # 公共配置（AI 链、pgvector、限流、管理员名单）
│           ├── application-local.yml # 本地：H2 + 内存向量库 + 容错 Redis
│           ├── application-prod.yml  # 生产：连接池/优雅停机/健康分级/Redis 排除项
│           └── schema.sql            # 生产幂等建表脚本
├── frontend/                         # Vue3 + Vite 前端
│   ├── package.json / vite.config.ts # 依赖、代理、手动 chunk、vitest 覆盖率阈值
│   ├── vercel.json / public/_redirects / public/_headers   # 部署路由与安全头
│   └── src/
│       ├── api/index.ts              # axios 封装（baseURL/VITE_API_BASE_URL 约定）
│       ├── auth.ts                   # JWT 存取与登出
│       ├── theme.ts / styles/        # 设计系统 v4（明暗主题 Token）
│       ├── router/index.ts           # 路由与登录守卫（redirect 参数）
│       ├── components/               # BaseButton/BaseInput/BaseCard/BaseTag/BaseTextarea…
│       ├── utils/                    # score/markdown/jsonRepair/agentSse/reportPdf…（均带单测）
│       └── views/                    # 18 个页面（Home/Interview/Agent/Jobs/Knowledge/Admin…）
├── .github/workflows/
│   ├── ci.yml                        # 后端 mvn test + 前端 vitest/vue-tsc/build（推送即跑）
│   └── keepalive.yml                 # 每 10 分钟保活 Render + 存活校验
├── docs/
│   ├── DEVELOPMENT.md                # 本文档
│   ├── architecture.md               # 架构文档
│   └── quality-assessment-2026-09/   # 六阶段质量评估报告（基线/修复记录/遗留风险）
├── render.yaml                       # Render Blueprint（服务定义 + 环境变量清单）
├── vercel.json                       # Vercel rewrites（/api → Render）
├── supabase-init.sql                 # Supabase 初始化 SQL（建表 + 启用扩展）
├── scripts/loadtest.mjs              # 压测脚本
├── start-dev.ps1 / start-dev.sh      # 一键开发启动
└── README.md / DEPLOY.md / HANDOVER.md / DELIVERY_REPORT*.md
```

---

## 4. 核心功能模块实现细节

### 4.1 认证与权限（AuthController / security 包）

- **注册/登录**：`POST /api/auth/register`、`/api/auth/login`。用户名 2-32 位（中文/字母/数字/下划线），密码强度校验；注册按 IP 限流（5 次/小时，`ClientIpUtil` 解析 XFF 需校验受信网段）。
- **JWT**：HS256，含 `iss=interview-guide`、`aud=interview-guide-client`、`role` claim。`JwtAuthFilter` 解析后写入 SecurityContext；登出黑名单走 Redis（失败降级为前端清 Token）。
- **管理员**：`app.admin-usernames`（环境变量 `APP_ADMIN_USERNAMES`，逗号分隔）命中的用户签发 `ROLE_ADMIN`；注册接口**拒绝保留用户名**防止抢注；种子管理员 `SEED_ADMIN_USERNAME/PASSWORD` 仅在账号不存在时创建。
- **未认证响应**：`AuthenticationEntryPoint` 统一返回 401 + JSON（前端登录守卫携带 `redirect` 参数）。

### 4.2 模拟面试与 AI 评估（InterviewController / InterviewService）

- **出题** `POST /api/interview/questions`：输入简历文本 + 岗位描述（均经 `PromptSanitizer` 消毒与截断 2000 字），AI 返回 JSON 数组（`category/difficulty/keyPoints/referenceAnswer`），经 `JsonRepairUtil` 修复解析（等长掩码防误判）。
- **评估** `POST /api/interview/evaluate`：支持可选 `imageUrl` 多模态。流程：SSRF 校验 → 存储域白名单（P1-08）→ **服务端换签 10 分钟签名 URL**（P2-06）→ `Media(mimeType, URI)` 交给 AI。
- **提示流** `POST /api/interview/ask/stream`（SSE）：递归追问提示，冷启动重试需 `clearTimeout` 防误杀（P2-22）。
- **并发保护**：所有 AI 调用经 `AiConcurrencyGuard`（全局 5 许可，`call()` 排队超时 30s，超时抛业务异常）+ 每用户限流（`aiLimiter`，30 次/分钟）。
- **SSE 双层限流**（P1-01/02）：`SseConcurrencyGuard` 全局 20 并发 + 每用户 1 并发；`onCompletion` 统一释放（onError/onTimeout 也会触发 onCompletion，避免重复释放）。

### 4.3 AI 降级链与 Embedding（config/AiConfig）

- **Chat 降级链** `app.ai.chain`：按顺序尝试，`api-key` 为空的节点自动跳过；主节点失败自动降级。每个节点显式 HTTP 超时（连接 10s / 读 240s，可配 `app.ai.connect-timeout-seconds`、`app.ai.read-timeout-seconds`），保证闸门许可必然释放。
- **Embedding（独立 Bean，显式装配）**：
  - 优先读 `app.ai.embedding.base-url/api-key`（即 `AI_EMBEDDING_*` 环境变量），未配置回退 `spring.ai.openai.*`；
  - base-url **不要带 `/v1`**（OpenAiApi 自动拼接 `/v1/embeddings`，代码已兼容剥离误配）；
  - 显式超时（连接 10s / 读 60s）+ 有限重试（2 次尝试、0.5s 间隔），杜绝默认重试模板 10 次指数退避 ≈5 分钟的挂起。
- **模型矩阵事实**：Agnes 仅提供 chat/图像/视频（无 embedding 端点）；B.AI 仅有 chat 协议。当前配置：chat=Agnes `agnes-2.5-flash`（可升级 `agnes-3.0-flash`），embedding=硅基流动 `BAAI/bge-m3`（1024 维）。

### 4.4 知识库 RAG（KnowledgeController / RagSearchService）

- **导入** `POST /api/knowledge/import`（简单模式）与 `/import/batch`（Markdown 分块）：文档 metadata 写入 `userId` 实现隔离；去重预检（相似度 ≥ `RAG_DEDUP_THRESHOLD:0.90` 跳过，每批最多 50 次预检）；统一走 `addToVectorStore`（容量上限 `maxDocuments` 保护）。
- **检索** `GET /api/knowledge/search`：`similaritySearch` 带 `userId` 过滤表达式（P2-14 防跨用户泄露）。
- **错题本**：评估分低于阈值的题目自动关联会话生成错题总结。
- **向量存储**：pgvector，`initialize-schema: true` 启动自动建表（HNSW + COSINE + 1024 维）。**换 embedding 模型/维度时必须先 `DROP TABLE vector_store` 再重启**。

### 4.5 求职智能体（service/agent）

- `AgentService`：ReAct 循环（模型输出动作 JSON → `AgentTools` 执行 → 观察结果 → 迭代），取消标志 + 心跳保活（15s comment ping）；SSE 事件流 `meta/start/token/done/error`。
- 会话与消息持久化（AgentConversationEntity/AgentMessageEntity），消息保存后 bump 会话 `updated_at`（P2-16）。

### 4.6 岗位聚合（service/job）

- `JobAgentService`：多渠道岗位拉取（内置精选数据源 + 可配置第三方渠道）、去重、入库（唯一约束 + 互斥刷新锁）；定时刷新每 1 小时（`JOB_REFRESH_DELAY`）。
- `JobClassifyService`：AI 分类（**注入 ChatModel 接口**，严禁注入具体实现类，否则上下文启动失败）+ 规则兜底，定长槽位按 index 配对（P2-13）。
- 管理端 `/api/admin/jobs/*` 支持手动刷新（管理员绕过 5 分钟限流）、上下架、删除。

### 4.7 文件存储与签名 URL（SupabaseStorageService）

- `upload()`：PUT 到 Supabase Storage，文件名清洗防穿越，命名空间 `interview/{sha256(userId)}/…`（B-08 防越权写）。
- `createSignedUrl(publicUrl, ttl)`：`POST /storage/v1/object/sign/{bucket}/{path}` 换签短时效 URL；非本系统 URL/路径穿越/失败一律回退原公开 URL（平滑切换）。
- `isOwnPublicUrl()`：SSRF 白名单（仅本系统存储域 + `/object/public/` 路径）。
- 消费点：`/evaluate` 服务端换签（10 分钟）；`/upload-image` 返回 `url + signedUrl`（1 小时预览）；前端预览优先用 `signedUrl`。

### 4.8 横切组件

| 组件 | 说明 |
|---|---|
| `GlobalExceptionHandler` | 业务异常（BusinessException）返回友好消息；未知异常统一 500 + 日志 |
| `PerUserRateLimiter` | 滑动窗口限流（refresh 5 分钟/用户；evaluate/followup/questions 30 次/分钟），带过期键清理防内存泄漏 |
| `JsonBodySizeLimitFilter` | JSON 请求体 1MB 上限（可配） |
| `UserBanRegistry` | 封禁名单 Redis 持久化 + 内存缓存 |
| `AiKeySanitizerPostProcessor` | 启动时扫描并打码日志中的 AI 凭据 |
| 健康检查 | `/api/info`（匿名轻量）、`/api/health`（匿名存活）、`/api/health/detail`（认证，DB/Redis/JVM） |
| 监控 | Micrometer + Prometheus（`/actuator/prometheus`，生产仅暴露 health,info） |

---

## 5. 代码规范与提交规范

### 5.1 后端代码规范

1. 分层：Controller 只做参数校验/编排，业务在 Service，数据访问在 Repository；禁止跨层调用。
2. 所有阻塞 IO（HTTP/DB/Redis）必须有超时；新增外部依赖必须先定义降级行为。
3. 业务错误抛 `BusinessException`（消息面向用户）；禁止吞异常返回假成功。
4. Prompt 输入一律经 `PromptSanitizer.sanitize()`；输出 JSON 一律经 `JsonRepairUtil`（**AI JSON 必须双引号**，禁止单引号/未转义控制字符）。
5. 日志不打印密钥/Token；新增配置项必须同步 `render.yaml`/`application*.yml` 与本文档 §7.3。
6. 新增/修改代码必须携带对应单元测试（见 §6），后端 JaCoCo 行覆盖门槛 **80%**（低于即构建失败）。

### 5.2 前端代码规范

1. 组件：页面放 `views/`，复用基础组件放 `components/`（BaseButton/BaseInput…，带单测）；纯函数工具放 `utils/` 且**必须配 `*.test.ts`**。
2. 样式使用设计系统语义 Token（`styles/variables.css`），禁止硬编码色值（暗色主题会破损）。
3. 渲染 AI 返回的 Markdown 必须经 DOMPurify 消毒（6 处 v-html 已全部消毒，新增处同样）。
4. API 调用统一走 `src/api/index.ts` 的 axios 实例；SSE 使用 `agentSse.ts`（与 axios baseURL 同源约定）。
5. 提交前本地必须通过：`npx vue-tsc --noEmit` + `npm run test:run` + `npm run build`。

### 5.3 Git 提交规范（Conventional Commits）

格式：`类型(范围): 描述`，正文说明**问题 / 解决思路 / 验证方法**三段。

```
类型：feat | fix | docs | test | refactor | chore | perf | deps
范围：security | ai | sse | rag | storage | job | frontend | coverage | monitor | deploy …

示例：
fix(ai): 显式装配 Embedding 客户端，超时+有限重试根除知识导入 5 分钟挂起

问题：……（根因、证据）
解决思路：……（改了什么、为什么安全）
验证：mvn test 612/612 全绿 + 覆盖率门槛通过；部署后线上复测……
```

- 每个独立修复/功能一个独立 commit；禁止为凑指标修改测试断言或删除失败用例。
- 分支策略：开发在 `main` 直接提交（个人项目），多人协作时使用 feature 分支 + PR。

---

## 6. 测试流程

### 6.1 后端（Maven + JUnit5 + Mockito + JaCoCo 0.8.12）

```bash
# 全量测试（当前 612 例）+ JaCoCo 报告 + 80% 行覆盖门槛（低于即失败）
mvn test -f backend/pom.xml

# 单跑某测试类
mvn test -f backend/pom.xml "-Dtest=AgentControllerTest"

# 覆盖率报告位置
backend/target/site/jacoco/index.html
```

- 测试全部为纯单元/切片测试（`@WebMvcTest`、`@DataJpaTest`、Mockito），**不需要**真实 PostgreSQL/Redis/网络。
- 覆盖率门槛：`BUNDLE LINE COVEREDRATIO ≥ 0.80`（pom 中 jacoco check，绑定 test 阶段）。
- `ApplicationContextSmokeTest` 验证完整 Spring 上下文可启动（防「启动崩」回归）。

### 6.2 前端（Vitest + vue-tsc + v8 coverage）

```bash
cd frontend
npm run test:run          # 单次运行（当前 241 例）
npm run coverage          # 含覆盖率阈值：statements/branches/functions/lines 均 ≥ 80%
npx vue-tsc --noEmit      # 类型检查（CI 同款）
npm run build             # 产物验证（需设置 VITE_API_BASE_URL 占位）
```

- 覆盖率统计范围：`src/utils/**`、`src/api/**`、`src/auth.ts`（vite.config.ts 中配置）。

### 6.3 CI（GitHub Actions，推送到 main 自动执行）

| Job | 内容 |
|---|---|
| Backend Maven Test | JDK 21 + `mvn -B -ntp test`，上传 surefire 报告 |
| Frontend Vitest + Build | Node 24 + `npm ci` → `npm audit --audit-level=high`（高危漏洞阻断）→ `vue-tsc` → `npm run coverage` → `npm run build` |

> 任一 Job 失败即红；推送后务必等 CI 绿再视为完成。

### 6.4 手工验收清单（发版前）

1. `GET /api/health` 返回 UP；
2. 注册 → 登录 → 上传/粘贴简历 → AI 出题（约 15-120s）；
3. 评估带附图（多模态）→ 错题本可见；
4. 知识库导入 3 条 → RAG 检索命中；
5. 智能体对话 SSE 流式正常；
6. 管理后台登录（管理员账号）→ 岗位刷新。

---

## 7. 部署环境要求与配置清单

### 7.1 云服务依赖

| 服务 | 用途 | 免费层要点 |
|---|---|---|
| Render | 后端 Docker Web Service（新加坡区） | 512MB 内存、15 分钟无请求休眠、构建 500 分钟/月 |
| Supabase | PostgreSQL + pgvector + Storage（bucket: resumes） | 数据库 500MB、存储 1GB；需启用 `vector` 扩展 |
| Upstash | Redis（TLS） | AI 缓存/封禁/限流，不可用自动降级 |
| Vercel | 前端主托管（rewrites 代理 /api） | — |
| Cloudflare Pages | 前端备用托管 | 需设置 `VITE_API_BASE_URL` 直连后端（需后端 CORS 放行） |
| Agnes AI | Chat 模型（agnes-2.5-flash） | 免费无限量；平台 platform.agnes-ai.com |
| 硅基流动 | Embedding（BAAI/bge-m3） | 免费额度；cloud.siliconflow.cn |
| GitHub Actions | CI + 保活定时任务 | 公开仓库免费 |

### 7.2 生产环境变量全表（Render，Blueprint：render.yaml）

| 变量 | 值 / 说明 | sync |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | 固定 |
| `AI_API_KEY` | Agnes Key（chat 兜底/Embedding 回退） | secret |
| `AI_BASE_URL` | `https://apihub.agnes-ai.com`（**不带 /v1**） | 固定 |
| `AI_CHAT_MODEL` | `agnes-2.5-flash`（可选 `agnes-3.0-flash`） | 固定 |
| `AI_EMBEDDING_BASE_URL` | `https://api.siliconflow.cn`（**不带 /v1**） | secret |
| `AI_EMBEDDING_API_KEY` | 硅基流动 Key | secret |
| `AI_EMBEDDING_MODEL` | `BAAI/bge-m3` | secret |
| `AI_EMBEDDING_DIMENSIONS` | `1024`（必须与模型输出一致） | 固定 |
| `AI_BAI_API_KEY` | **当前已删除**（Render→api.b.ai 网络不可达）；恢复时重新添加 | — |
| `DATABASE_URL` | `jdbc:postgresql://<pooler-host>:5432/postgres?user=postgres.<REF>&password=<PWD>` | secret |
| `REDIS_URL` | `rediss://default:<PWD>@<host>:<port>`（Upstash） | secret |
| `REDIS_SSL` | `true` | 固定 |
| `JWT_SECRET` | 强随机 ≥32 字符 | secret |
| `JWT_EXPIRATION_MS` | `86400000`（24h） | 固定 |
| `APP_ADMIN_USERNAMES` | `小吴同学`（逗号分隔可多个） | 固定 |
| `SEED_ADMIN_USERNAME` / `SEED_ADMIN_PASSWORD` | 种子管理员（仅首次创建时生效） | secret |
| `SUPABASE_URL` / `SUPABASE_SERVICE_KEY` / `SUPABASE_BUCKET` | `https://<REF>.supabase.co` / service key / `resumes` | secret |
| `JAVA_OPTS` | `-Xmx220m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0`（Metaspace 不得低于 128m） | 固定 |

前端托管侧：Vercel 用 rewrites 同源代理（`VITE_API_BASE_URL` 留空）；Cloudflare Pages 需设置 `VITE_API_BASE_URL=https://interview-guide-backend.onrender.com`（后端 CORS 已放行 `*.pages.dev`）。

### 7.3 改配置的三处同步原则

新增任何配置项，必须同步更新：① `application.yml`（或 prod/local）；② `render.yaml`；③ 本文档 §7.2 表格。

---

## 8. 部署步骤

### 8.1 本地部署（开发联调）

```bash
# 终端 1：后端（local profile：H2 + 内存向量库 + Redis 容错）
cd backend
mvn spring-boot:run -D"spring-boot.run.profiles=local"        # PowerShell 用 -D"spring-boot.run.profiles=local"
# Linux/macOS：mvn spring-boot:run -Dspring-boot.run.profiles=local
# 验证：curl http://localhost:8080/api/health

# 终端 2：前端（5173，已配置 /api 代理到 localhost:8080）
cd frontend
npm run dev
# 打开 http://localhost:5173
```

- 前端代理目标可用 `VITE_API_BASE_URL` 覆盖（如指向测试环境后端）。
- local profile 下 AI 功能需要 `AI_API_KEY`（至少 Agnes）；RAG 需要全部 `AI_EMBEDDING_*`。

### 8.2 测试环境部署（本地容器化验证）

```bash
# 构建后端镜像（多阶段，模拟 Render 构建）
cd backend
docker build -t interview-guide-backend:dev .

# 以 prod 等价配置运行（连测试用 Supabase/Upstash，变量占位见 §7.2）
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL='jdbc:postgresql://<pooler>:5432/postgres?user=postgres.<REF>&password=<PWD>' \
  -e REDIS_URL='rediss://...' -e REDIS_SSL=true \
  -e JWT_SECRET='<32+ chars>' \
  -e AI_API_KEY='<agnes-key>' \
  -e AI_EMBEDDING_BASE_URL='https://api.siliconflow.cn' \
  -e AI_EMBEDDING_API_KEY='<sf-key>' -e AI_EMBEDDING_MODEL='BAAI/bge-m3' -e AI_EMBEDDING_DIMENSIONS=1024 \
  -e SUPABASE_URL='https://<REF>.supabase.co' -e SUPABASE_SERVICE_KEY='<key>' -e SUPABASE_BUCKET=resumes \
  -e APP_ADMIN_USERNAMES='<admin>' \
  interview-guide-backend:dev

# 前端产物验证
cd frontend && npm run build && npm run preview   # http://localhost:4173
```

验收：`/actuator/health` UP；注册/登录/出题全流程可走通。

### 8.3 生产环境部署（Render + Vercel/CF Pages）

**首次部署**

1. Supabase：新建项目 → SQL Editor 执行 `supabase-init.sql`（建表 + `CREATE EXTENSION vector`）→ Storage 创建 `resumes` bucket。
2. Render：Dashboard → New → **Blueprint** → 选本仓库（自动读取 render.yaml）→ 逐个填入 `sync: false` 的 secret 变量（§7.2）→ Deploy。
3. Upstash：创建 Redis（TLS）→ 把连接串填入 `REDIS_URL`，`REDIS_SSL=true`。
4. Vercel：导入仓库，框架 Vite，`vercel.json` 已含 rewrites；Cloudflare Pages 备用（构建命令 `npm run build`，输出 `dist`，环境变量 `VITE_API_BASE_URL`）。
5. 验证：`https://interview-guide-backend.onrender.com/api/health` UP；前端登录/出题/RAG 全流程。

**日常发布（标准流程）**

1. 本地完成开发 + 全量测试（§6）+ commit（§5.3）；
2. `git push origin main` → GitHub Actions CI 自动跑（必须绿）；
3. CI 通过后 docker-build 工作流构建镜像并通过 **Deploy Hook** 触发 Render 自动重新部署（autoDeploy 亦开启）；
4. 等 Render 部署 Live（约 4-5 分钟）→ 抽查 `/api/health` 与一个 AI 接口；
5. 线上验证通过即发布完成；失败回滚：Render Dashboard → Deploys → 选上一个成功部署 → Redeploy。

**保活与监控**：`.github/workflows/keepalive.yml` 每 10 分钟 ping `/api/info`（唤醒循环最长 8 分钟）+ `/api/health` 存活校验，失败会 GitHub 邮件告警；深度组件体检（DB/Redis/JVM）在需认证的 `/api/health/detail`，可用监控账号定时调用。

**密钥泄露应急**：立即在对应平台吊销旧 Key → 创建新 Key → 更新 Render 环境变量（触发重部署）→ 若 Key 曾入库，仓库内移除明文并评估是否清洗 git 历史（filter-repo + force push，需所有者授权）。

---

## 9. 常见问题解决方案（FAQ）

> 以下均为本项目真实踩坑记录，按「现象 → 根因 → 处理」组织。

**Q1 Windows 下跑 `mvn test` 报 "The forked VM terminated without properly saying goodbye"**
路径含中文/特殊字符时 Java agent 参数编码损坏。处理：将仓库移到纯 ASCII 路径，或用 8.3 短路径执行（`for %I in (.) do @echo %~sI` 获取），或给 JaCoCo 指定 ASCII 路径 `-Djacoco.destFile=...`。

**Q2 AI 出题/评估请求挂起数分钟后 500**
逐层排查：① AI 提供方 Key 是否有效（curl 直连 `/v1/chat/completions`）；② 降级链中是否有节点网络不可达（每个不可达节点会吃满 240s 读超时——曾因 Render→api.b.ai 挂起导致每次请求空等 8 分钟，处理方式是从 `app.ai.chain`/环境变量中移除该节点）；③ 查 `FallbackChatModel` 的 "尝试降级" 日志确认走到了哪个节点。

**Q3 知识库导入返回「成功导入 0 条」**
`RagSearchService.importKnowledge` 捕获异常后返回 0（日志有 `知识库导入失败：...`）。已见根因：① embedding base-url 误带 `/v1`（已代码兼容剥离）；② embedding 模型在提供方不存在（如把 Agnes 的 text-embedding-3-small 指到硅基流动）；③ pgvector 列维度与 `AI_EMBEDDING_DIMENSIONS` 不符（换模型后需 `DROP TABLE vector_store` 重建）。改完配置后记得 `Save, rebuild, and deploy`。

**Q4 后端启动失败 / 启动即崩溃**
① `app.ai.chain` 所有节点 api-key 均为空 → 启动抛 IllegalStateException（至少配一个有效节点）；② `JAVA_OPTS` Metaspace < 128m（Spring Boot+AI+Security 类加载失败）；③ `DATABASE_URL` 格式错误（Supabase Session pooler 用户名是 `postgres.<REF>`）；④ Redis 自动装配已从 prod 排除（RedisConfig 提供容错工厂），不要手动加回。

**Q5 生产 Redis 报错 / Token 登出无效**
Upstash 必须走 TLS：`REDIS_SSL=true`；Redis 挂掉时缓存/封禁自动降级（登录不受影响），但 Token 黑名单会失效——尽快恢复 Redis。

**Q6 Render 免费实例休眠、首次请求超 50 秒**
keepalive 工作流每 10 分钟保活；唤醒循环最长等 8 分钟。若保活任务大量失败：查看是否「冷启动 > 预算」或健康检查解析格式不匹配（保持与 `/api/health` 匿名格式 `{"status":"UP"}` 一致）。

**Q7 前端登录后刷新丢状态 / CORS 报错**
生产走 Vercel rewrites 同源代理（baseURL 留空）；Cloudflare Pages 直连方案必须设置 `VITE_API_BASE_URL` 且后端 CORS 包含对应域名。本地 5173 已配置 `/api` 代理，无需 CORS。

**Q8 管理员账号登录后没有管理权限**
`APP_ADMIN_USERNAMES` 未包含该用户名（区分大小写），或 Token 签发早于配置变更——重新登录获取新 JWT（role 在签发时写入）。注册接口会拒绝保留用户名，属防抢注设计。

**Q9 CI 红了：npm audit / vue-tsc / coverage**
`npm audit --audit-level=high`：升级依赖（Dependabot PR）或 `overrides`；`vue-tsc`：类型错误在 CI 才暴露的，本地先跑 `npx vue-tsc --noEmit`；coverage：低于 80% 阈值，为新代码补测试。

**Q10 附图预览白图 / 评估报「图片地址无效」**
预览走 `signedUrl`（1 小时时效，过期重新上传即可）；「图片地址无效」说明 imageUrl 不在本系统存储域白名单（P1-08），只允许 `/api/interview/upload-image` 返回的 URL。bucket 转私有后，AI 下载走服务端换签，无需前端处理。

**Q11 PowerShell 里 curl POST JSON 报 400 参数错误**
PowerShell 的引号转义会破坏 JSON。把 JSON 写入文件用 `curl.exe --data "@file.json"`（注意用 `curl.exe` 而非 `curl` 别名）。

**Q12 想升级/更换 chat 模型**
改 Render `AI_CHAT_MODEL`（如 `agnes-3.0-flash`，实测 JSON 直出且无 reasoning 开销）→ 保存自动重部署。更换前先用 curl 直连该模型做一次 JSON 输出验证。

---

## 10. 技术支持与联系方式

| 渠道 | 说明 |
|---|---|
| **项目仓库 Issues** | https://github.com/w020316/interview-guide-AI-interview-platform/issues —— 优先渠道，提问请附 CI 链接/日志片段（脱敏） |
| **项目所有者** | GitHub @w020316（仓库 Owner，Render/Supabase/Upstash/各 AI 平台所有者账号持有人） |
| **架构与历史决策** | `docs/architecture.md`、`docs/quality-assessment-2026-09/`（含基线、修复记录、遗留风险）、`HANDOVER.md` |
| Agnes AI（chat 模型） | 文档 https://wiki.agnes-ai.com ｜ 平台 https://platform.agnes-ai.com ｜ 邮箱 support@agnes-ai.com |
| 硅基流动（embedding） | https://cloud.siliconflow.cn ｜ 文档 https://docs.siliconflow.cn ｜ 控制台工单 |
| Render | https://dashboard.render.com ｜ https://status.render.com ｜ support@render.com |
| Supabase | https://supabase.com/dashboard ｜ https://status.supabase.com ｜ Dashboard 工单 |
| Upstash | https://console.upstash.com ｜ https://status.upstash.com ｜ 控制台工单 |
| Vercel / Cloudflare Pages | 各自 Dashboard；状态页 vercel-status.com / cloudflarestatus.com |

**故障上报模板**：环境（本地/测试/生产）→ 现象与时间点 → 请求/响应片段（脱敏）→ Render 日志 Request ID → 已尝试的排查步骤。

---

*本文档由 2026-09-19 全面质量评估与线上运维闭环过程沉淀而来，配置与命令均为当时实测值；项目演进后请按 §7.3 的同步原则更新。*

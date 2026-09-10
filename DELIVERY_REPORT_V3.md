# AI 智能面试辅助平台 — 交付报告 V3

> **版本**：V3.0
> **日期**：2026-07-05
> **仓库**：https://github.com/w020316/interview-guide-AI-interview-platform
> **部署**：https://vercel.com/w020316s-projects/interview-guide-ai-interview-platform
> **提交**：`f978133` fix: 修复AI返回非标准JSON导致解析失败 + UI重设计消除AI感

---

## 一、本次交付核心成果

### 1.1 关键 Bug 修复（用户报错）

**问题**：简历上传成功，但 AI 返回内容无法解析为标准 JSON，报错：
```
Unexpected token ''', ..."gestion": '项目 '教材ING"... is not valid JSON
```

**根因**：AI 模型（agnes-2.0-flash）返回的 JSON 使用了**单引号**、**中文引号**、**中文冒号**，标准 `JSON.parse` 无法解析。

**解决方案**：双层 JSON 修复机制
- **后端**：新建 [`JsonRepairUtil`](backend/src/main/java/com/example/interview/util/JsonRepairUtil.java)，采用两阶段状态机修复
  - 阶段1：key 单引号字符串非贪婪匹配（`'key':` → `"key":`）
  - 阶段2：value 单引号字符串贪婪匹配到结构字符（`'项目 '教材ING''` → `"项目 '教材ING'"`）
  - 修复中文引号 `“”`、中文冒号 `：`、尾随逗号、Markdown 代码块、未加引号的 key
- **前端**：[`ResumeView.vue`](frontend/src/views/ResumeView.vue) 增加二次修复兜底，确保即使后端修复失败也能解析
- **Prompt 强化**：所有 AI 调用明确要求使用 ASCII 双引号，禁止单引号和中文引号

**验证**：13 个单元测试覆盖所有报错场景，全部通过

### 1.2 AI 响应速度优化

**问题**：AI 实时提示答案生成速度太慢

**优化措施**：
| 优化项 | 改动 | 效果 |
|--------|------|------|
| Prompt 精简 | 去除冗长系统提示，限制回答 300 字 | 减少 token 数，加快首 token 响应 |
| 心跳保活 | 每 15s 发送 SSE 注释行 | 避免 Vercel/Nginx 60s 超时断开 |
| 即时反馈 | 立即发送 `start` 事件 | 用户感知响应时间为 0 |
| 流式输出 | 逐 token 推送 | 首字出现即开始展示 |
| Redis 缓存 | 简历分析缓存 30min，题目缓存 1h | 重复请求秒级返回 |

### 1.3 前端 UI 重设计（消除 AI 感）

**问题**：原设计"太 AI"——紫色渐变光晕、玻璃态、网格背景

**重设计方向**：编辑风专业平台（参考 Notion / Linear / Stripe Docs）

| 维度 | 旧设计（AI slop） | 新设计（专业风） |
|------|-------------------|------------------|
| 主色 | 紫色渐变 `#4f46e5 → #06b6d4` | 深墨绿纯色 `#0f766e` |
| 背景 | 纯白 + 光晕装饰 | 暖米白 `#fafaf9` + 极细微纸张纹理 |
| 标题字体 | 无衬线系统字体 | 衬线字体 Source Han Serif |
| 卡片 | 玻璃态 backdrop-filter | 实色 + 1px 边框 + 精细阴影 |
| 装饰 | 光晕、网格、浮动动画 | 去除，用排版和留白建立层次 |
| 按钮 | 渐变背景 + 上浮动效 | 实色背景 + 颜色加深 |

**重设计页面**（9 个）：
- [`HomeView.vue`](frontend/src/views/HomeView.vue) — 首页 Hero + 特性 + 流程
- [`LoginView.vue`](frontend/src/views/LoginView.vue) — 登录注册双栏
- [`ResumeView.vue`](frontend/src/views/ResumeView.vue) — 简历分析
- [`InterviewView.vue`](frontend/src/views/InterviewView.vue) — 模拟面试
- [`HistoryView.vue`](frontend/src/views/HistoryView.vue) — 历史记录
- [`KnowledgeView.vue`](frontend/src/views/KnowledgeView.vue) — 知识库
- [`ProfileView.vue`](frontend/src/views/ProfileView.vue) — 个人中心
- [`NotFoundView.vue`](frontend/src/views/NotFoundView.vue) — 404 页
- [`ResumeHistoryView.vue`](frontend/src/views/ResumeHistoryView.vue) — 简历历史
- [`App.vue`](frontend/src/App.vue) — 全局导航栏 + 页脚
- [`variables.css`](frontend/src/styles/variables.css) — 设计系统 v3

### 1.4 安全加固

- **AuthController**：新增用户名长度校验（3-32 字符）、字符校验（字母数字下划线）、密码强度校验（6-64 字符）、邮箱格式校验
- **JWT**：HS256 密钥长度校验（≥32 字节），启动时失败快速报错
- **越权防护**：所有 CRUD 接口从 SecurityContext 提取 userId，拒绝客户端传入
- **文件上传**：扩展名白名单 + Content-Type 校验 + PDF magic bytes 校验 + 10MB 大小限制
- **CORS**：白名单制，精确域名而非通配符
- **限流**：Bucket4j 纯内存限流拦截器

---

## 二、功能清单

### 2.1 已实现功能模块

| 模块 | 功能点 | 状态 |
|------|--------|------|
| **用户认证** | 注册 / 登录 / 登出（JWT 无状态） | ✅ |
| **用户认证** | 密码 BCrypt 加密 | ✅ |
| **用户认证** | token 过期自动跳转登录 | ✅ |
| **简历分析** | 纯文本粘贴分析 | ✅ |
| **简历分析** | PDF/TXT 文件上传解析（PDFBox） | ✅ |
| **简历分析** | AI 多维度评分（4 维度） | ✅ |
| **简历分析** | Redis 缓存（30min） | ✅ |
| **简历分析** | 历史记录持久化 + 列表 + 详情 | ✅ |
| **模拟面试** | AI 生成个性化面试题 | ✅ |
| **模拟面试** | SSE 流式实时提示 | ✅ |
| **模拟面试** | 答题评估（3 维度） | ✅ |
| **模拟面试** | 面试会话管理（创建/查询/完成） | ✅ |
| **知识库** | RAG 向量检索（pgvector） | ✅ |
| **知识库** | 语义搜索 + 增强问答 | ✅ |
| **知识库** | 批量导入知识分块 | ✅ |
| **个人中心** | 用户信息展示 | ✅ |
| **历史记录** | 面试会话历史 + 题目详情 | ✅ |
| **安全** | 限流（Bucket4j） | ✅ |
| **安全** | 全局异常处理（统一响应格式） | ✅ |
| **可观测** | Prometheus 指标 + Actuator 健康检查 | ✅ |
| **可观测** | AI 调用计数 + 耗时计时器 | ✅ |
| **文档** | Swagger UI / OpenAPI 3 | ✅ |

### 2.2 技术栈

**前端**：Vue 3.4 + TypeScript + Vite 5 + Vue Router 4 + Element Plus 2.7 + Axios + markdown-it

**后端**：Spring Boot 3.3.6 + Java 21 + Spring AI 1.0 + Spring Security + Spring Data JPA + PostgreSQL + pgvector + Redis + JWT + Bucket4j + PDFBox + SpringDoc OpenAPI + Micrometer Prometheus

**部署**：Vercel（前端）+ Render/Fly.io（后端）+ Supabase（PostgreSQL）+ Upstash（Redis）

---

## 三、测试结果

### 3.1 后端单元测试

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
```

| 测试类 | 用例数 | 状态 |
|--------|--------|------|
| `JsonRepairUtilTest` | 13 | ✅ 全部通过 |
| `ResumeAnalysisServiceTest` | 2 | ✅ 全部通过 |
| `ResumeServiceTest` | 6 | ✅ 全部通过 |
| `RagSearchServiceTest` | 4 | ✅ 全部通过 |
| `RateLimitInterceptorTest` | 4 | ✅ 全部通过 |
| `GlobalExceptionHandlerTest` | 5 | ✅ 全部通过 |

**关键测试覆盖**（用户报错场景）：
- ✅ `{'suggestion': '项目 '教材ING''}` → `{"suggestion": "项目 '教材ING'"}`
- ✅ 中文引号 `{'suggestion'：'项目'}` → `{"suggestion": "项目"}`
- ✅ 中文双引号 `{"suggestion"："项目"}` → `{"suggestion": "项目"}`
- ✅ Markdown 代码块剥离
- ✅ 尾随逗号清理
- ✅ 未加引号的 key 自动加引号
- ✅ 综合场景（单引号 + 中文引号 + 尾随逗号 + Markdown）

### 3.2 前端构建

```
✓ 1758 modules transformed.
✓ built in 5.87s
```

- 构建成功，无错误
- 代码分割：vue-vendor / element-plus / markdown 独立 chunk
- gzip 后总大小约 470KB

### 3.3 编译验证

- 后端 `mvn compile`：✅ 成功
- 前端 `npm run build`：✅ 成功

---

## 四、问题修复记录

| 编号 | 问题 | 严重级别 | 修复方案 | 文件 |
|------|------|----------|----------|------|
| P0-01 | AI 返回单引号/中文引号导致 JSON 解析失败 | **致命** | 新建 JsonRepairUtil 两阶段状态机修复 | `JsonRepairUtil.java` |
| P0-02 | 简历上传成功但无结果展示 | **致命** | 后端修复 + 前端二次修复兜底 | `ResumeView.vue` |
| P1-01 | AI 实时提示响应慢 | **高** | 精简 prompt + 心跳保活 + 即时反馈 | `InterviewController.java` |
| P1-02 | AI 返回 Markdown 代码块包裹 JSON | **高** | JsonRepairUtil 剥离 ```json 代码块 | `JsonRepairUtil.java` |
| P1-03 | 持久化的 JSON 可能不合法 | **高** | ResumeService 持久化前修复 | `ResumeService.java` |
| P1-04 | UI 设计"太 AI" | **高** | 全面重设计，深墨绿主色 + 衬线标题 | 9 个 Vue 文件 + variables.css |
| P2-01 | 注册无密码强度校验 | **中** | 增加 6-64 字符限制 | `AuthController.java` |
| P2-02 | 注册无用户名校验 | **中** | 增加 3-32 字符 + 字符白名单 | `AuthController.java` |
| P2-03 | 注册无邮箱格式校验 | **中** | 增加正则校验 | `AuthController.java` |
| P2-04 | SSE 长连接可能被代理超时断开 | **中** | 15s 心跳保活 | `InterviewController.java` |
| P2-05 | AI prompt 未约束输出格式 | **中** | 强化 prompt，明确禁止单引号/中文引号 | 3 个 Service |

---

## 五、部署信息

- **Git 仓库**：https://github.com/w020316/interview-guide-AI-interview-platform
- **最新提交**：`f978133`（已推送到 main）
- **Vercel 部署**：自动触发，预计 2-3 分钟完成
- **前端访问**：https://interview-guide-ai-interview-platform.vercel.app

---

## 六、交付物清单

| 交付物 | 路径 | 说明 |
|--------|------|------|
| 源代码 | Git 仓库 | 已推送至 main 分支 |
| 后端单元测试 | `backend/src/test/` | 34 个测试全部通过 |
| JSON 修复工具 | `JsonRepairUtil.java` | 核心修复组件 + 13 个测试 |
| 设计系统 | `variables.css` | v3 设计 token |
| 前端页面 | `frontend/src/views/` | 9 个页面全部重设计 |
| 交付报告 | 本文件 | `DELIVERY_REPORT_V3.md` |

---

## 七、验收结论

✅ **用户报错已修复**：AI 返回非标准 JSON 导致的解析失败问题彻底解决
✅ **AI 响应优化**：SSE 流式 + 心跳保活 + prompt 精简，显著提升响应速度
✅ **UI 重设计**：消除 AI 感，采用专业编辑风设计
✅ **安全加固**：注册校验 + JWT + 越权防护 + 限流 + 文件上传校验
✅ **测试通过**：34 个后端测试 + 前端构建均通过
✅ **已部署**：代码已推送，Vercel 自动部署中

**项目状态：可交付**

---

## 八、v1.22.0 增量交付（2026-09-10）：AI 主模型升级 B.AI + 招聘广场智能体

### 8.1 AI 模型降级链

调研 B.AI 平台（https://chat.b.ai/key）免费模型后选定最优组合：

| 顺位 | 提供方 | 模型 | 费用 | 选型依据 |
|------|--------|------|------|----------|
| 1（主） | B.AI | GLM-5.3-Flash | 0 Credits（限免） | 320B/18B 激活、1M 上下文、多模态；Terminal-Bench 2.1 84.3、Toolathlon 78.4，agentic/编码/JSON 输出能力最强 |
| 2（次） | B.AI | Qwen3.8-Flash | 0 Credits（限免） | 多模态、1M 上下文；SWE-bench Pro 62.5，免费兜底 |
| 3（兜底） | Agnes AI | agnes-2.0-flash | 免费 | 原主模型自动降为末位；继续承担知识库 Embedding（text-embedding-3-small） |

实现：FallbackChatModel（call/stream 双通道自动降级）+ AiProviderProperties（app.ai.chain 可配置）；主模型失败自动切换次模型。两个 B.AI 模型均已实测 200 可用。

### 8.2 招聘信息智能体（三模块）

1. **平台对接模块**：JobPlatformAdapter 适配器体系 — 内置秋招精选数据源（18 家企业 2027 届校招，含官方申请入口）+ 智联招聘/前程无忧/BOSS直聘 HTTP 适配器（配置第三方招聘数据服务 endpoint 即启用，字段宽容映射）
2. **数据整合与更新模块**：(platform, external_id) 幂等 upsert；AI 智能分类补全行业/职位类型/标签（规则分类兜底）；每 6 小时定时刷新 + 手动刷新；过期自动下架 + 陈旧数据清理
3. **展示与筛选模块**：/api/jobs 多条件筛选（关键词/行业/职位类型/地点/招聘类型/来源）+ 前端「招聘广场」页面（秋招精选 Tab、截止日期倒计时、岗位详情弹窗、官方申请入口直达）

### 8.3 验证记录

- 后端 mvn compile 通过；Spring 上下文 local profile 启动成功
- 实测：POST /api/jobs/refresh 入库 18 条；GET /api/jobs、/api/jobs/meta、行业+地点组合筛选均返回正确
- 前端 ue-tsc + vite build 通过（JobsView 8.21 kB chunk）；Vitest 213 个测试全部通过
- 新增表 job_posting 已同步 SchemaInitializer 与 schema.sql

### 8.4 部署注意

- Render 环境变量需新增 AI_BAI_API_KEY（render.yaml 已声明，sync:false 需在 Dashboard 填入）
- 前端版本号 changelog 1.22.0 与后端 APP_INFO_VERSION 已同步

---

## 九、v1.23.0 增量交付（2026-09-10）：Career Copilot 求职智能体

> 设计文档：docs/AGENT_DESIGN.md（需求分析/架构/技术选型/测试策略完整版）

### 9.1 功能概述

对话式求职智能体，自然语言直达平台能力，支持自主决策调用 5 大工具：

| 工具 | 能力来源 | 场景 |
|------|----------|------|
| searchJobs | 招聘智能体（v1.22.0） | 找岗位/查企业/秋招推荐 |
| searchKnowledge | 知识库 RAG | 技术面试题解答 |
| getMyInterviewStats | 面试统计 | 薄弱点分析 |
| listWrongQuestions | 错题本 | 针对性复习 |
| getUpcomingInterviews | 面试日历 | 日程规划 |

### 9.2 核心实现

- **编排**：AgentService（记忆窗口 12 条 + System Prompt 用户画像预注入 + SSE 心跳流式）
- **工具**：AgentTools（@Tool 注解，每请求实例绑定 userId 避免 ThreadLocal 跨线程串扰；结果裁剪 ≤1500 字符防上下文爆炸）
- **协议**：AgentController（/api/agent/chat/stream SSE + 会话 CRUD，复用 v1.16 心跳保活与 error 优雅完成模式）
- **存储**：agent_conversation / agent_message 表（SchemaInitializer 幂等建表）
- **模型**：复用 v1.22.0 降级链，GLM-5.3-Flash 原生 function calling

### 9.3 测试记录

- 后端：AgentToolsTest（11 用例：裁剪/空数据/异常降级）+ AgentControllerTest（3 用例：认证/防越权/异步 SSE）✅
- 全量回归：后端 264 测试通过；前端 220 测试通过 + vue-tsc 0 错误 ✅
- 新增前端 agentSse 解析器 7 用例（跨块缓冲/心跳注释/CRLF/flush）✅

### 9.4 安全与可靠性设计

- 工具全只读；userId 从 JWT 提取，会话归属校验防 IDOR；用户输入 PromptSanitizer 消毒
- SSE 并发信号量保护（20）；对话落库失败不影响响应

### 9.5 上线验证与两处关键修复

1. **B.AI 不支持 API 级 function calling**（实测 glm-5.3-flash / qwen3.8-flash 带 tools 参数均 400 Invalid request body）→ 改为自研 ReAct 提示词层工具协议（模型输出动作 JSON → JsonRepairUtil 修复 → 本地 dispatch → 观察回填，最多 6 轮）
2. **岗位筛选生产 500**：@Query ":param IS NULL" 模式 PostgreSQL 无法推断 NULL 参数类型（H2 本地正常）→ 改用 JpaSpecificationExecutor 动态查询（commit c187875）

线上最终验证：智能体自主调用 searchJobs → 返回结构化岗位表格（含申请链接），多轮会话记忆生效，SSE 分块输出正常；前端 pages.dev 已含 AgentView（1.23.0）。

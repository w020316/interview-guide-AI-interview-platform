# AI 智能面试辅助平台 · 技术/架构/API 文档

> 版本：1.20.0 ｜ 日期：2026-08-31

---

## 一、系统架构

```
┌──────────────────────────────────────────────────────────┐
│                        浏览器（前端）                       │
│         Vue 3 + TypeScript + Vite + Element Plus           │
│       v4 设计系统 · 按需分包 · 暗色模式 · 无障碍            │
└───────────────┬──────────────────────────────┬────────────┘
                │ 同源 /api（Vercel rewrites）  │ 直连跨域（VITE_API_BASE_URL，CORS）
                │ 或 EventSource SSE           │
┌───────────────▼──────────────────────────────▼────────────┐
│              Web 网关（Vercel / Cloudflare Pages）          │
└──────────────────────────┬────────────────────────────────┘
                           │
┌──────────────────────────▼────────────────────────────────┐
│              Spring Boot 3.3 后端（Render/本地）            │
│  Controller → Service → Repository/Redis/AI/RAG            │
│  JWT 鉴权 · 限流拦截器 · 全局异常 · 指标埋点 · 幂等建表       │
└──────┬──────────────┬──────────────┬──────────────┬────────┘
       │              │              │              │
┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼──────────┐
│ PostgreSQL │ │ pgvector   │ │   Redis     │ │  LLM + Embedding│
│ 业务数据    │ │ 向量检索/知识库│ │ 缓存/限流   │ │  AI 服务 + RAG  │
└────────────┘ └────────────┘ └────────────┘ └─────────────────┘
```

### 分层与模块
- `controller`：REST/S 流式接口（SSE）、统一 `Result<T>`
- `service`：业务逻辑（简历/岗位/面试/知识库/收藏/日历/统计）
- `repository`：Spring Data JPA
- `security`：JWT 过滤 + `JwtUtil`（subject=userId，含 issuer/audience）
- `interceptor`：限流（IP 维度）
- `config`：Security / VectorStore / Redis / Metrics / SchemaInitializer / CORS
- `util`：HashUtil / TextUtil / JsonRepairUtil / PromptSanitizer

---

## 二、API 一览（前缀 `/api`）

### 认证 `auth`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /auth/register | 注册（实时校验用户名/密码/邮箱） |
| POST | /auth/login | 登录，签发 JWT |
| POST | /auth/logout | 登出 |

### 简历 `resume`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /resume/analyze | 简历文本分析 |
| POST | /resume/upload | 上传解析（PDF/TXT/MD/HTML，≤10MB） |
| GET  | /resume/history | 简历历史 |
| GET  | /resume/{id} | 简历详情（IDOR→403） |
| POST | /resume/optimize | 简历优化 |
| POST | /resume/import-url | URL 导入 |

### 岗位分析 `job`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /job/analyze | JD 解析 |
| POST | /job/gap | 能力差距诊断 |
| POST | /job/letter | 求职信/内推信生成 |

### 模拟面试 `interview` / `session`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /interview/questions | 生成面试题（难度 30/50/20） |
| POST | /interview/evaluate | 答案评估 |
| POST | /interview/ask/stream | SSE 流式对话（error/token/done 事件） |
| POST | /session/create | 创建会话 |
| GET  | /session/list | 会话历史 |
| GET/PUT | /session/{sessionId}(...) | 会话详情/完结 |
| GET/POST | /session/{sessionId}/questions | 题目查询/持久化 |
| POST | /session/answer | 保存作答 |

### 知识库 `knowledge`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | /knowledge/search | 知识检索 |
| POST | /knowledge/ask | 知识库问答（RAG） |
| POST | /knowledge/import | 文档导入 |
| POST | /knowledge/import/batch | 批量导入 |
| GET  | /knowledge/wrong-questions | 错题本（threshold 参数） |
| GET  | /knowledge/question-summary | 趋势维度统计 |
| GET  | /knowledge/recent-questions | 最近题目 |

### 收藏 `favorite` ⭐
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | /favorite/list | 我的收藏 |
| GET  | /favorite/ids | 已收藏题目 ID 集合 |
| POST | /favorite/toggle | 收藏/取消（快照式，IDOR 校验） |

### 面试日历 `calendar/event` ⭐
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | /calendar/event/list | 我的日程 |
| POST | /calendar/event | 新增日程 |
| PUT  | /calendar/event/{id} | 编辑/改状态（越权→400） |
| DELETE | /calendar/event/{id} | 删除（越权→400） |

### 统计 `stats`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /stats/dashboard | 仪表盘统计 |
| GET | /stats/trend | 成绩趋势（得分走势） |

### 健康 `health`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /info | 心跳（GitHub Actions keepalive 每 10min 探测防休眠） |

> 统一返回 `Result<T> = { code, message, data }`；鉴权用 `Authorization: Bearer <JWT>`；403 视为鉴权失效。

---

## 三、关键技术特性
- **数据库**：prod 关闭 `ddl-auto`，启动经 `SchemaInitializer` 幂等建表（新表无需人工迁库）。
- **RAG**：pgvector 向量库 + Embedding 维度管理；检索失败自动降级（不影响主流程）。
- **流式**：SSE `EventSource` 消费，20 并发信号量限流防虚拟线程耗尽。
- **安全**：JWT（subject=userId、含 issuer/audience）、Prompt 注入消毒、目录穿越防御、CORS 白名单。
- **可观测**：Micrometer 指标埋点 + AppInfo 版本信息。
- **部署**：前端 Vercel/Cloudflare Pages，后端 Render；直连场景设 `VITE_API_BASE_URL`。
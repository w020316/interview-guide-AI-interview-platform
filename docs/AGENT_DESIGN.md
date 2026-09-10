# AI 智能体（Career Copilot）设计文档

> 版本：v1.23.0 · 2026-09-10
> 状态：已实现并上线

---

## 一、需求分析

### 1.1 核心需求

| 编号 | 需求 | 说明 |
|------|------|------|
| R1 | 对话式求职助手 | 用户以自然语言与智能体交互，无需在多个功能页间跳转 |
| R2 | 平台能力工具化 | 智能体可调用平台已有能力（岗位检索/知识库 RAG/面试统计/错题本/面试日历）作为工具 |
| R3 | 自主决策（ReAct） | 模型根据用户意图自主决定：直接回答 or 调用哪个工具、以什么参数调用 |
| R4 | 多轮会话记忆 | 保留每用户的对话历史，支持上下文追问 |
| R5 | 流式响应 | SSE 逐 token 输出，工具调用过程对用户可见 |
| R6 | 模型高可用 | 复用 v1.22.0 模型降级链（B.AI GLM-5.3-Flash → Qwen3.8-Flash → Agnes） |
| R7 | 数据隔离与安全 | 严格用户隔离（userId 从 JWT 提取）、Prompt 注入防护、工具参数白名单校验 |

### 1.2 应用场景

1. 「帮我找深圳的 Java 秋招岗」→ 调用岗位检索工具，汇总推荐
2. 「我哪些知识点比较薄弱？」→ 调用面试统计工具，分析薄弱分类
3. 「Redis 持久化怎么答？」→ 调用知识库 RAG 检索，基于知识作答
4. 「我最近有哪些面试安排？」→ 调用面试日历工具
5. 综合场景：「结合我的薄弱点和目标岗位，给我一份复习计划」→ 多工具组合 + 推理

---

## 二、系统架构

```
┌─────────────── 前端 AgentView.vue ───────────────┐
│  会话列表 │ 流式消息渲染 │ 工具调用状态提示      │
└────────────────────┬─────────────────────────────┘
                     │ SSE (fetch + POST) / REST
┌────────────────────▼─────────────────────────────┐
│ AgentController  /api/agent/**（JWT 认证）        │
│  - POST /chat/stream   流式对话                   │
│  - GET  /conversations 会话列表                   │
│  - GET  /conversations/{id}/messages 历史消息     │
│  - DELETE /conversations/{id} 删除会话            │
├──────────────────────────────────────────────────┤
│ AgentService（编排核心）                          │
│  1. 加载会话历史（最近 12 条）                     │
│  2. 组装 System Prompt（人设+工具指引+用户画像）   │
│  3. ChatClient.tools(...) 注册工具                │
│  4. Spring AI 内部工具执行循环（ReAct）            │
│  5. 流式返回 + 异步落库                            │
├──────────────────────────────────────────────────┤
│ AgentTools（工具层，@Tool 注解）                  │
│  searchJobs │ searchKnowledge │ getMyStats       │
│  listWrongQuestions │ getUpcomingInterviews      │
├──────────────────────────────────────────────────┤
│ FallbackChatModel（v1.22.0 降级链）               │
│ agent_conversation / agent_message（PostgreSQL）  │
└──────────────────────────────────────────────────┘
```

### 模块划分

| 模块 | 职责 |
|------|------|
| `controller/AgentController` | 协议层：SSE 流式、会话 CRUD、参数校验 |
| `service/agent/AgentService` | 编排：记忆装配、Prompt 构建、工具注册、流式推送、落库 |
| `service/agent/AgentTools` | 能力层：5 个只读工具，包装既有 Service，输出裁剪防 token 爆炸 |
| `entity/AgentConversationEntity` / `AgentMessageEntity` | 会话与消息持久化 |
| `repository/AgentConversationRepository` / `AgentMessageRepository` | 数据访问（批量查询防 N+1） |

---

## 三、技术选型

| 维度 | 选型 | 理由 |
|------|------|------|
| AI 模型 | B.AI GLM-5.3-Flash（主）+ Qwen3.8-Flash（次）+ Agnes（兜底） | 复用 v1.22.0 降级链；GLM-5.3-Flash 原生支持 function calling 与流式工具调用，0 Credits 免费 |
| 开发框架 | Spring AI 1.0.0 `@Tool` 注解 + ChatClient 内部工具执行循环 | 与现有 spring-ai-starter-model-openai 零新增依赖；框架自动完成「模型决策→工具执行→结果回填」循环 |
| 流式协议 | SSE（SseEmitter，POST + fetch 流式读取） | 与现有 InterviewController SSE 模式一致，前端复用 fetch 流式解析 |
| 记忆存储 | PostgreSQL（启动时幂等建表，SchemaInitializer） | 与项目现有存储方案一致，无新增中间件 |
| 交互渲染 | Vue 3 + 原有 Markdown 渲染工具 | 复用 utils/markdown，保持风格统一 |

---

## 四、核心算法与交互逻辑

### 4.1 ReAct 工具调用循环（由 Spring AI 框架内部执行）

```
用户消息 ──► 模型推理 ──┬─► 无需工具 ──► 流式输出最终回答
                        └─► 需要工具 ──► 发出 tool_call(JSON)
                              │
                              ▼
                     AgentTools 执行（≤200 行/结果裁剪）
                              │
                              ▼
                     工具结果回填上下文 ──► 回到模型推理（最多 6 轮）
```

### 4.2 关键设计决策

1. **System Prompt 注入用户画像**：薄弱分类 Top3、面试次数、平均分预注入，模型无需先调工具即可个性化回答，减少工具调用次数、降低延迟
2. **工具结果裁剪**：每个工具输出限制行数/字符（岗位 ≤8 条、错题 ≤10 条等），防止单次工具调用撑爆上下文
3. **消息历史窗口**：仅携带最近 12 条历史（6 轮对话），平衡记忆与 token 成本
4. **安全**：所有工具为只读查询；userId 一律从 JWT SecurityContext 提取，工具参数不接受 userId；用户输入经 PromptSanitizer 消毒
5. **可靠性**：SSE 复用心跳保活 + error 事件优雅完成模式（v1.16 方案）；对话落库异步执行，失败不影响响应

### 4.3 数据模型

- `agent_conversation`：id, user_id, title（首条消息截断 30 字）, created_at, updated_at
- `agent_message`：id, conversation_id, role(USER/ASSISTANT), content, created_at

---

## 五、测试策略

| 层级 | 覆盖内容 | 工具 |
|------|----------|------|
| 单元测试 | AgentTools 各工具的裁剪逻辑与空数据边界；会话标题截断 | JUnit5 + Mockito |
| 集成测试 | AgentController 认证（401）、会话 CRUD、SSE 端点可达 | MockMvc + H2 |
| 性能验证 | 首 token 延迟、SSE 并发（受 SSE_MAX_CONCURRENT=20 信号量保护） | 手工压测 + 线上验证 |
| 前端测试 | SSE 流式解析工具函数单测 | Vitest |

---

## 六、部署与运维

- 无新增外部依赖与环境变量（模型链复用 AI_BAI_API_KEY）
- 新增表 `agent_conversation`/`agent_message` 由 SchemaInitializer 启动时自动创建（PostgreSQL），H2 本地由 JPA ddl-auto 建表
- 可扩展性：新增工具只需在 AgentTools 增加一个 `@Tool` 方法（只读、结果裁剪）；更换模型只需改 `app.ai.chain` 配置
- 可维护性：编排/工具/协议三层分离，工具层不依赖协议层

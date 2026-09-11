# AI 功能模块检查与验证报告

> 范围：interview-guide 后端全部 AI 相关代码模块
> 方法：模块梳理 + 逐文件完整性检查 + 集成验证 + 全量功能测试 + 配置/依赖核查
> 日期：2026-09-11　基线：后端单测 316/316、前端 vue-tsc 0 错误

---

## 一、AI 功能模块清单

| # | 模块 | 文件 | 功能 | 调用方 |
|---|---|---|---|---|
| 1 | 模型降级链 | config/AiConfig.java | 多厂商降级链（B.AI 主 → Qwen 次 → Agnes 兜底） | 全局 ChatClient |
| 2 | 降级执行器 | ai/FallbackChatModel.java | 同步/流式降级（流式已发出 token 不降级） | ChatClient 底层 |
| 3 | 并发闸门 | ai/AiConcurrencyGuard.java | 全局 AI 并发控制（5 许可） | 各 AI Service |
| 4 | 面试出题/评估/追问 | service/InterviewService.java | 简历+岗位出题、作答评分、深挖追问 | InterviewController, AgentTools |
| 5 | 简历分析 | service/ResumeAnalysisService.java | AI 多维评分（Redis 缓存 + JSON 修复） | ResumeController |
| 6 | 岗位分析/差距诊断/求职信 | service/JobAnalysisService.java | JD 分析、简历对比、Cover Letter | JobAnalysisController |
| 7 | 知识库 RAG | service/RagSearchService.java | 向量检索 + 带上下文回答 + 用户隔离 | KnowledgeController, AgentTools |
| 8 | 求职智能体 | service/agent/AgentService.java + AgentTools.java | ReAct 循环 + 10 项工具（搜岗/联网/匹配/出题/追问/知识/统计/错题/日程） | AgentController |
| 9 | 联网搜岗 | service/job/WebJobSearcherService.java | jsoup 抓智联 SSR 岗位 | AgentTools |
| 10 | 简历岗位匹配 | service/job/JobMatchService.java | 纯规则技能/学历匹配打分 | AgentTools |
| 11 | 多模态附图评估 | service/InterviewService.evaluateAnswerWithImage | 图片+文本综合评估 | InterviewController |

---

## 二、完整性检查结论

### ✓ 功能完整性（算法/模型/预处理/API）
- **所有 AI 服务**均：输入经 `PromptSanitizer` 消毒（防注入）、长文本 `TextUtil.truncate` 截断、输出经 `JsonRepairUtil` 修复与校验、空值兜底（不向前端回 null）。
- **模型调用**统一走 `ChatClient` + `FallbackChatModel` 降级链，主模型失败自动降级，流式已发 token 不切换模型（防拼接错乱）。
- **并发控制**：各 AI Service 纳入全局 `AiConcurrencyGuard`（5 许可）。
- **缓存**：面试题（Redis + userId 隔离）、简历分析（Redis + userId 隔离）、RAG 去重（相似度阈值）。
- **用户隔离**：RAG 检索/导入、缓存 key、越权校验均含 userId，防跨用户串扰。

### ✓ 集成与数据流转
- 前端 `/agent`、`/interview`、`/resume`、`/knowledge`、`/job/*` 等接口与后端 AI Service 链路完整贯通（SSE 流式 / REST / FormData / multipart 上传）。
- 岗位数据：`JobAgentService.refresh`（内置 + 第三方适配器 + 联网抓取）→ 入库 → 智能体/招聘广场检索/简历匹配复用。
- 智能体 ReAct：模型 → 工具注册表（AgentTools.dispatch）→ 10 项工具 → 观察回填，最多 8 轮。

### ✓ 配置/依赖/环境
- `application.yml`：spring.ai 模型 + embedding 配置完整；降级链 3 档均正确。
- `render.yaml`：AI_BAI_API_KEY / AI_API_KEY / AI_BASE_URL / AI_EMBEDDING_MODEL 均已配置（key 为 `sync:false` 需在 Render 手动填入）。
- 依赖：`spring-ai-starter-model-openai`、`spring-ai-starter-vector-store-pgvector`、`jsoup`、`pdfbox` 在 pom 齐备。
- 生产 profile（application-prod.yml）排除 pgvector/Redis 自动装配避免 OOM，RAG 退化为内存 SimpleVectorStore（有 max-documents 500 上限保护）。

---

## 三、发现的问题与修复

| ID | 级别 | 问题 | 修复 |
|---|---|---|---|
| AI-01 | P2 | `JobAnalysisService` 使用**独立** `AI_SEMAPHORE(5)`，与全局 `AiConcurrencyGuard` 并存，拆分并发预算（总并发上限被放大，免费模型限流下是隐患） | ✅ 改为全局 `AiConcurrencyGuard.call()`，移除独立信号量，与其它 AI Service 统一共享 5 许可 |
| AI-05 | P1 | **并发闸门未真正统一**（v1.31.4 复核）：`InterviewService.generateQuestions`、`ResumeAnalysisService.generateOptimizedResume` 各保留 `AI_SEMAPHORE(5)`，与全局闸门形成 **3 个独立信号量**，最坏并发 15 而非 5 | ✅ 三处统一为 `AiConcurrencyGuard.call()`，删除两处冗余信号量字段；同步移除不可达的 `catch(InterruptedException)` |

### 已确认正常（无问题）
- AI-02 | 模型降级链完整，`FallbackChatModel` 启动时空链会显式抛异常（不会静默瞎跑）。
- AI-03 | 所有 AI 服务空响应/失败都有兜底文案，不影响前端。
- AI-04 | Prompt 均用 StringBuilder 拼接 + sanitize，无 String.format 注入风险。

---

## 四、功能测试结果
- 后端全量单测 **316/316** 通过。
- AI 专项测试：`InterviewServiceTest`（出题/评估/追问/空值/注入）、`ResumeAnalysisServiceTest`、`JobAnalysisServiceTest`（12 例）、`RagSearchServiceTest`、`AgentToolsTest`（23 例）、`WebJobSearcherServiceTest`、`InterviewControllerTest` 等全部通过。
- 前端 `vue-tsc --noEmit` **0 错误**。

---

## 五、结论
项目 AI 功能模块完整、集成顺畅、防护齐备（消毒/修复/限流/隔离/降级/缓存/容量保护），在多模型降级、并发收敛后运行条件满足。本轮修复 1 个并发控制一致性问题（JobAnalysisService 纳入全局闸门），全部通过回归。
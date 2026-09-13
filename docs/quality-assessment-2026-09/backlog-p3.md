# P3 建议 Backlog — AI 智能面试辅助平台（interview-guide）

> 来源：阶段一代码审查（2026-09-13，详见 phase1-code-review.md）
> 约定：P3 不阻塞阶段二，按批次随迭代消化；标注〔5〕的条目建议优先（涉及可观测性失真或一致性）

## 后端（28）

| # | 位置 | 问题 / 建议 |
|---|---|---|
| B-01〔5〕 | common/Result.java:22-24 + GlobalExceptionHandler | 业务错误 HTTP 状态恒 200、BusinessException→500；改 ResponseEntity + 业务码，避免污染 5xx 告警 |
| B-02 | GlobalExceptionHandler:30-34 | IllegalArgumentException message 回显；统一固定文案 |
| B-03 | InterviewEventController:57-77 | 日志 status 白名单 + 字段长度校验 |
| B-04 | KnowledgeController:60-69 | /import 单条 8KB 截断（与 /import/batch 对齐） |
| B-05〔5〕 | KnowledgeController:170-196 | recent-questions/wrong-questions 全量加载后内存 limit，改仓储分页 |
| B-06 | AgentController 删除会话 | 静默成功改 404/403 语义 |
| B-07 | StatsController:155-163 | trend dimension 白名单校验 |
| B-08 | InterviewController:99-100 等 | Map 值强转改 String.valueOf，类型错误返回 400 |
| B-09 | RateLimitInterceptor | 多实例时迁 Redis 令牌桶（Upstash 已具备） |
| B-10 | PerUserRateLimiter:52-80 | 键数达上限后拒绝新增或改 Caffeine maximumSize |
| B-11 | application-local.yml:73-77 | local 弱 JWT 默认密钥：去掉默认或校验已知弱值拒绝启动 |
| B-12 | InterviewController:186-217 | 作答附图拒绝 image/svg+xml + magic bytes 校验 |
| B-13 | JobClassifyService:85-87 | AI 分类调用纳入 AiConcurrencyGuard + 独立指标 |
| B-14 | AgentService:209-226 | 闸门中断异常与降级链失败异常类型区分（自定义异常） |
| B-15〔5〕 | InterviewService:159-165 / ResumeAnalysisService:149-152 | 缓存命中不计入 AI 调用指标，消除统计失真 |
| B-16 | InterviewService:73-74 | 缓存 key 拼接加分隔符（对齐 ResumeAnalysisService 做法） |
| B-17 | InterviewSessionService:72-95 | finishSession/saveQuestions 补 service 层归属校验 |
| B-18 | JobAgentService:139-204 | 岗位刷新批量化（findByIn + saveAll + JDBC batch） |
| B-19 | JobMatchService:62-71 | 循环外复用 toLowerCase；es/ts 按词边界匹配 |
| B-20 | JobAgentService:239-241 + AdminService:126-132 | location/用户名搜索 LIKE 转义对齐 |
| B-21〔5〕 | InterviewSessionService:133-142 | questionSummary 改 JPQL 聚合或短缓存（智能体每轮对话都调用） |
| B-22 | JobFavoriteEntity:33-35 | 删除冗余索引 idx_job_favorite_user（含存量库 DROP INDEX） |
| B-23 | RedisConfig:85,105-110 | rediss 默认端口改 6379；回退改显式禁用缓存 + ERROR；补 connect timeout |
| B-24 | application-prod.yml:29-38 | Hikari 池 2→5-8（Supabase 配额允许时）；去掉 connection-test-query |
| B-25〔5〕 | SchemaInitializer | 引入 Flyway/Liquibase 管理全量变更（消除生产结构漂移防线缺口） |
| B-26 | application.yml:16-18 | 默认 profile 静默故障：仿 local 提供 H2 或文档强制 profile |
| B-27 | InterviewSessionService:181-225 | 无评分数据时平均分置 null 而非 0.0 |
| B-28 | WebJobSearcherService:63-80 | 4 候选源虚拟线程并行化 + 整体 10s 截止 |

## 前端（9）

| # | 位置 | 问题 / 建议 |
|---|---|---|
| F-01 | ResumeHistoryView:324-346 / InterviewView:594-600 | scoreColor/Gradient 改用 utils/score.ts 统一实现 |
| F-02 | AdminView:159-162 | getErr 改用 api 层 getErrMessage |
| F-03 | utils/format.ts | 死代码：各视图真正接入或删除 |
| F-04 | utils/weakCategories.ts:26-33 | 分类均分仅对有分题目计算 |
| F-05 | theme.ts:37-43 | localStorage.setItem 包 try-catch |
| F-06 | AgentView:13-22 | 会话项补 role/tabindex/键盘事件 |
| F-07 | package.json:16 | 删除冗余 @types/dompurify |
| F-08 | index.html:11 | preconnect 域名与 .env 联动（注释标注或构建期注入） |
| F-09〔5〕 | 版本号四源不一致 | package.json 1.28.0 / changelog 1.32.0 / pom 1.31.3 / APP_INFO 1.23.0，固定单一来源 |

## 关联数据层死配置（随迭代决策）

- knowledge_doc 表 + schema.sql 种子数据无代码读取（P2-18 关联）：补 seeding 或删除表与 DDL
- pgvector 依赖/配置（P2-17 关联）：恢复启用或彻底移除，见 phase2-fix-record.md 暂缓说明

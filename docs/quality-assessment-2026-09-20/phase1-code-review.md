# 阶段一 · 代码审查报告 — AI 智能面试辅助平台（interview-guide）

> **审查日期**：2026-09-20　**基准 commit**：`54effbe`（工作区干净）
> **审查轮次**：第二轮（上轮 2026-09-13，产物见 `docs/quality-assessment-2026-09/phase1-code-review.md`）
> **本轮定位**：复评 —— 与上轮 75 项发现对照，标注「已修 / 仍存 / 新增 / 失效」

---

## 一、审查范围（本轮实测规模）

| 维度 | 数量 |
|---|---|
| 后端主代码 | 100 个 `.java` |
| 后端测试代码 | 61 个测试类 / 672 个用例 |
| 后端包分布 | service 27 / config 17 / controller 13 / repository 10 / entity 10 / util 9 / ai 5 / security 3 / common 3 / interceptor 1 / dto 1 |
| 前端视图 | 19 个 View |
| 前端组件 | 7 个 |
| 前端工具模块 | 24 个（`src/utils/`） |
| 前端测试 | 25 个文件 / 270 个用例 |
| 部署与配置 | `backend/Dockerfile`、`render.yaml`、`vercel.json`、`application*.yml`、`schema.sql`、`.github/workflows/{ci,keepalive}.yml` |

## 二、审查方法

静态分析工具与逐模块人工审查相结合（总规则要求）：

| 工具/手段 | 实测结果 |
|---|---|
| `mvn -o -B -ntp test`（含 JaCoCo） | 672/672 通过（隔离数据源后） |
| `vue-tsc --noEmit` | 0 错 |
| `vite build` | 成功 8.30s |
| `npm audit --audit-level=high`（官方源） | 0 漏洞 |
| `help:effective-pom` | 用于核对插件实际生效配置 |
| `mvn -X` 抓取 surefire fork 命令行 | 用于定位 JaCoCo 失效根因 |
| 分支并行人工审查 | 4 路（ai/config+部署链、service、controller/安全/数据层、前端） |

---

## 三、已独立验证的关键发现（证据链完整）

### F-B｜JaCoCo 覆盖率门槛**静默失效**，pom 声明的 80% 门槛本地从未执行

| 项 | 内容 |
|---|---|
| **级别** | **P2（一般）** — 门槛在 **CI 有效**、在 **本地静默失效**，导致本地无法验证覆盖率 + 认知错位 + 环境污染（定级依据见下方「CI 侧实测举证」） |
| **现象** | 每次 `mvn test` 日志均出现：<br>`jacoco:0.8.12:report (report) — Skipping JaCoCo execution due to missing execution data file.`<br>`jacoco:0.8.12:check (check) — Skipping JaCoCo execution due to missing execution data file:...\target\jacoco.exec` |
| **矛盾点** | `-X` 抓取的 fork 命令行**确实带了 agent**：<br>`D:\xm\bin\java -javaagent:D:\\xm\\maven-repo\\org\\jacoco\\org.jacoco.agent\\0.8.12\\org.jacoco.agent-0.8.12-runtime.jar=destfile=D:\\xm\\wz\\AI智能面试辅助平台\\interview-guide\\backend\\target\\jacoco.exec ...` |
| **根因（已定位）** | 项目绝对路径含中文 `AI智能面试辅助平台`。surefire 3.2.5 通过 **`cmd.exe /X /C "..."`**（`LegacyForkNodeFactory`）拉起 fork JVM，`cmd.exe` 的代码页把 `-javaagent` 参数里的中文路径破坏 → JaCoCo 产物写到**乱码目录**且**文件名被截断一位** |
| **决定性证据** | 磁盘上实际存在：<br>① 乱码目录 `D:\xm\wz\AIÖÇÄÜÃæÊԸ¨Öúƽ̨\interview-guide\backend\target\`（= `AI智能面试辅助平台` 的 GBK/UTF-8 错编码）<br>② 其中文件名为 **`jacoco.exe`**（应为 `jacoco.exec`，**末位字符丢失**）<br>③ 另有历史遗留 `D:\xm\jacoco.exec`（2026-09-13，610KB） |
| **影响 1** | `backend/pom.xml:196-234` 声明的 **BUNDLE LINE ≥ 0.80** 规则**本地永不执行** → 覆盖率回退不会被本地构建拦住 |
| **影响 2** | 上轮最终交付报告「pom 已固化 80% BUNDLE 行覆盖门槛（低于即构建失败）」**在本地不可复现**；本地「BUILD SUCCESS」**不等于**覆盖率达标 |
| **影响 3** | **本地绿 ≠ 覆盖率达标**：开发者看到的本地 `BUILD SUCCESS` 完全不能证明覆盖率合规，容易误判 |
| **影响 4** | 每次本地构建向 `D:\xm\wz\` 与 `D:\xm\` 泄漏垃圾目录/文件（乱码目录 + `jacoco.exec`） |
| **✅ CI 侧实测举证** | 拉取 HEAD 提交（`54effbe`）的 CI 运行日志（run `35489467331`，success，1m9s）：<br>`Loading execution data file /home/runner/work/.../backend/target/jacoco.exec`<br>`[INFO] All coverage checks have been met.`<br>→ **CI 环境中路径为纯 ASCII，JaCoCo 正常执行、80% 门槛确实生效**（故本轮基准中「后端行覆盖率 ≥ 80%」成立）；问题**仅存在于本地中文路径环境** |
| **建议修复** | 二选一（推荐 A）：<br>**A. 把 JaCoCo 产物路径显式改为 ASCII**：在 pom 中为 `prepare-agent`/`report`/`check` 统一配置 `destFile`/`dataFile` 为如 `D:/xm/jacoco/interview-guide.exec`（`${jacoco.destFile}` 与 `report.dataFile` 必须一致）；同时清理已产生的乱码目录与 `D:\xm\jacoco.exec`<br>**B. 让 surefire 不经 `cmd.exe` fork**（改 `-Dsurefire.forkNode` 或显式 `JarManifestForkConfiguration`），绕开代码页问题<br>并补一条**本地可执行的覆盖率校验**（如 `mvn -o verify` 纳入交付前检查清单） |

> 补充：`backend/pom.xml` 的 JaCoCo `report` 输出目录为 `target/site`（受 `maven-site-plugin` 配置影响），修复时一并确认为 ASCII 路径。

---

### F-C｜Embedding `dimensions` 未透传给 API —— 配置项名不副实，存在静默陷阱

| 项 | 内容 |
|---|---|
| **级别** | **P1（严重）** — 核心 RAG 链路，故障表现不透明 |
| **位置** | `backend/src/main/java/com/example/interview/config/AiConfig.java:348-400`（构建处 `395-399`；维度参数 `356`） |
| **问题** | 构造 embedding 模型时：<br>`OpenAiEmbeddingOptions.builder().model(embeddingModel).build()` —— **未调用 `.dimensions(...)`**。<br>注入的 `embeddingDimensions`（来自 `spring.ai.vectorstore.pgvector.dimensions`，默认 1536）**仅被用于 `log.info/log.warn`（第 370-377 行）**，完全没有传给 embedding API。 |
| **实际影响** | `AI_EMBEDDING_DIMENSIONS` **只决定 pgvector 建表列宽**；真正返回的向量维度由**模型默认值**决定。两者不一致时报「知识库导入 500 · 向量维度不匹配」，而配置项名字让人误以为它控制请求 → 极难定位 |
| **当前为何"能用"** | 生产用阿里云百炼 `text-embedding-v4`，其默认输出恰好是 1024，与 `render.yaml` 配置的 `AI_EMBEDDING_DIMENSIONS="1024"` 巧合一致 → **属"碰巧对齐"，非契约保证** |
| **告警覆盖不全** | 第 372 行的体检只在 `dimensions == 1536 && model 不含 text-embedding-3` 时告警；若配置 1024 而模型默认返回其他维度，**完全不告警** |
| **风险场景** | 更换 embedding 提供方/模型版本（其默认维度变化）→ 上线即失败，且日志无线索 |
| **建议修复** | 在 `OpenAiEmbeddingOptions.builder()` 链上补 `.dimensions(embeddingDimensions)`，使配置项与请求真正一致；并把体检改为「按提供方声明的默认维度」校验或启动时**实测一次 embedding 维度**与 pgvector 列宽比对（不一致则启动即报错，而非等导入时 500） |

---

### F-A｜`ApplicationContextSmokeTest` 未隔离数据源 —— 本地全量测试必红 + 污染真实数据

| 项 | 内容 |
|---|---|
| **级别** | **P2（一般）** |
| **位置** | `backend/src/main/resources/application-local.yml:18`（`jdbc:h2:file:D:/xm/data/interview` 固定共享文件库）<br>`backend/src/test/java/com/example/interview/ApplicationContextSmokeTest.java:38-41`（`@TestPropertySource` 只隔离向量库快照，**未隔离数据源**） |
| **现象** | 本机 local 开发服务在跑时（实测 PID 25588 占 8080/8090、持有该 H2 文件），`mvn test` 得 `672 例 / 670 通过 / 2 Error → BUILD FAILURE`；报错 `Unable to open JDBC Connection for DDL execution [IO Exception: "D:/xm/data/interview.mv.db" [90028-224]]`（H2 90028 = 文件被锁） |
| **成因** | v1.34.0（2026-09-20，**当日**）把 local 数据源由 `mem:` 改为 `file:`，但未同步为该测试补数据源隔离 |
| **二次影响** | 即使无锁冲突，该测试 `saveAndFlush` 会向**用户真实本地库**写入 `smoke-user` 记录 —— 与测试自己声明的「测试必须与真实数据隔离」自相矛盾 |
| **✅ 已验证** | 仅用 `SPRING_DATASOURCE_URL=jdbc:h2:mem:smokebaseline;DB_CLOSE_DELAY=-1` 覆盖后复测：**672/672 全绿、BUILD SUCCESS（1m09s）** → 证明这 2 个 Error **是测试隔离缺陷，不是业务代码回归** |
| **建议修复** | 测试内追加 `spring.datasource.url=jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1`（或 `@AutoConfigureTestDatabase`），彻底脱离 `D:/xm/data` |

---

## 四、前端批次审查结果（19 视图 + 7 组件 + 24 工具模块）

### 4.1 高优先级

| 级别 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| P1 | `src/utils/reportShare.ts` 整体（覆盖率仅 **23.25% stmts / 17.1% lines**） | 分享卡生成逻辑分支大量未覆盖，且属用户可见功能；回归无防线 | 补单测；若含动态 HTML 生成，明确其转义边界 |
| P2 | `src/views/InterviewView.vue` / `AgentView.vue`（SSE 消费处） | 流式回答超时/中断后的收尾与状态复位依赖后端超时；前端看门狗与后端超时未统一为常量 | 抽出统一超时常量并加看门狗兜底 |

### 4.2 中低优先级

| 级别 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| P2 | 多处 `views` | 重复实现 `scoreColor`/`scoreGradient`（`ResumeHistoryView.vue:324-346`、`InterviewView.vue:594-600` 等） | 统一收敛到 `src/utils/score.ts` |
| P2 | `src/views/AgentView.vue:13-22` | 会话列表项为可点击自定义元素，未补 `role`/`tabindex`/键盘事件 | 补可访问性属性 |
| P2 | `src/utils/weakCategories.ts:26-33` | 分类均分把无分题目当 0 参与计算，拉低薄弱维度判定准确性 | 仅对有分题目计算 |
| P3 | `src/theme.ts:37-43` | `localStorage.setItem` 未包 `try-catch`（隐私模式下抛异常） | 包 try-catch |
| P3 | `src/views/AdminView.vue:159-162` | 自行实现 `getErr`，与 api 层统一实现不一致 | 改用 api 层 `getErrMessage` |
| P3 | `src/utils/format.ts` | 疑似死代码 | 接入或删除 |
| P3 | `package.json:16` | 冗余依赖 `@types/dompurify`（DOMPurify 自带类型） | 移除 |
| P3 | `index.html:11` | `preconnect` 域名硬编码，与 `.env` 不联动 | 注释标注或构建期注入 |

### 4.3 版本号单一来源（上轮 F-09 **未收敛，且恶化**）

| 来源 | 值 |
|---|---|
| `backend/pom.xml` | `1.31.3` |
| `frontend/package.json` | `1.28.0` |
| `frontend/src/changelog.ts`（最新条目） | `v1.33.3` |
| 线上 `/api/info`（实测） | `1.0.0` |
| `application-local.yml` 注释 / `ApplicationContextSmokeTest` javadoc | `v1.34.0` |

→ **≥5 源不一致，跨度 1.28.0 ↔ 1.34.0**，且 `v` 前缀不统一。本轮建议定级 **P2** 并收敛为单一来源（构建期注入）。

---

## 五、后端批次审查结果

### 批次 3｜controller / security / interceptor / repository / entity / util / common（约 49 文件，深入审阅 32 个）

**P1**

| 位置 | 问题 | 修复建议 |
|---|---|---|
| `controller/InterviewController.java:202-238` | 作答附图上传**仅校验** `contentType.startsWith("image/")`，`image/svg+xml` 直接放行，且**全程无 magic bytes 校验** → 存储型 XSS / SSRF 入口 | 拒绝 `image/svg+xml`；按扩展名白名单（jpg/png/webp/gif）+ 校验文件头 |
| `controller/AuthController.java:235-238` + `security/JwtUtil.java` | 登出为**纯前端清 token**，无 refreshToken 机制也无黑名单；JWT 无状态、最长 24h → **被盗 token 无法吊销** | 引入 Redis 黑名单，或改为短时效 access + 可吊销 refresh |
| `interceptor/RateLimitInterceptor.java:34`、`AuthController.java:46,189` | 全部限流（全局 IP、`PerUserRateLimiter`、登录锁定 `loginFailMap`、`SseConcurrencyGuard`、`registerLimiter`）**皆为单实例内存计数** → 多实例下额度被 N 倍放大，**登录锁定可被分摊绕过** | 改用 Redis 共享计数 |

**P2**

| 位置 | 问题 | 修复建议 |
|---|---|---|
| `controller/InterviewController.java:102-103`；`JobAgentController.java:124`；`KnowledgeController.java:77` | 用 `(String) request.get(...)` / `Integer.parseInt` 直接强转，客户端传非字符串 → `ClassCastException` → **500**（应为 400） | 统一 `String.valueOf` 或 DTO + `@Valid`，类型错返 400 |
| `controller/AgentController.java:260-263` + `service/agent/AgentService.java:372-378` | 删除会话对**不存在/非本人**会话静默 no-op，仍返 200 成功 | 抛 `BusinessException`，返回明确错误 |
| `controller/InterviewEventController.java:74,60-72` | 日程 `status` **无白名单**（任意值入库）；`title/interviewer/location/note` **无长度上限**，超长致 DB 异常 → 500 | status 枚举白名单 + 字段长度校验 |
| `service/AdminService.java:128` | `listUsers` 的 keyword 拼 LIKE **未转义** `%`/`_`（同文件 `listJobs:83` 已转义，**二者自相矛盾**） | 与 listJobs 一致转义 |
| `entity/JobFavoriteEntity.java:33-35` | `uniqueConstraints(user_id, job_id)` 生成的复合索引已覆盖 `user_id` 前缀，单列 `idx_job_favorite_user` **冗余** | 删除冗余索引（含存量库 `DROP INDEX`） |
| `controller/InterviewSessionController.java:161-176` | `/answer` 直接用请求体 `questionId`，**未校验该题所属会话归属当前用户** → 可越权写入答案（**待验证**） | `saveAnswer` 内校验归属 |
| `controller/ResumeController.java:39,142-156` | 上传允许 `.html/.htm` 且无 magic bytes；`resumeText` 原样回传前端，若按 HTML 渲染 → 存储型 XSS | HTML 按纯文本提取存储，或禁止 HTML 上传 |
| `common/Result.java:22-28` | 业务错误经 `Result.error(...)` **直返 HTTP 200**、错误码仅置于 body，与异常路径（4xx/5xx）语义不一致 → 污染监控告警 | 校验失败用 `@ResponseStatus`/异常返回真实状态码 |
| `controller/ResumeController.java:246` + `util/SsrUrlValidator.java:19-21` | import-url 的 SSRF 仅**一次性 DNS 校验**，存在 DNS 重绑定 TOCTOU 残余 | 连接层绑定已校验 IP（如暂不修需在文档标注残余风险） |

**P3**

| 位置 | 问题 |
|---|---|
| `util/PromptSanitizer.java:30-42` | 基于有限正则的 prompt 注入防御，**释义即可绕过**；且 `evaluate`/`gap` 等接口未对所有用户输入净化（仅 stream 路径净化）。建议以 system prompt 边界声明为主防御 |
| `controller/AuthController.java:132` | 密码最小长度仅 6，强度不足（建议 ≥8） |
| `controller/StatsController.java:155` | `trend dimension` 未白名单（仅 Java 分支、不拼 SQL，无注入面，任意值静默回退 WEEK） |
| `util/ClientIpUtil.java:44` | 受信代理判定仅覆盖 IPv4 段与 `::1`，**内网 IPv6（`fc00::/7`）未识别** |

### 批次 2｜`service/` 业务层（`service`、`service/job`、`service/agent` 三包约 19 个主代码文件 + 调用方 4 个 controller）

**P1**

| 编号 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| S-01 | `InterviewSessionService`（finishSession / saveQuestions） | service 层**缺归属校验**，当前靠 controller 补偿 → 一旦新增调用方即出现越权写 | 在 service 层补归属校验（纵深防御） |
| S-02 | `KnowledgeController` recent-questions / wrong-questions 路径 | **全量加载后在内存里 limit/过滤**，且分页 `total` **失真** | 改仓储分页（JPQL 聚合 / Pageable） |
| S-03 | `JobAgentService` upsert 路径 | **N+1 写库**（逐条 save） | 批量化（`findByIn` + `saveAll` + JDBC batch） |

**P2**

| 编号 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| S-04 | `JobClassifyService` | AI 分类调用**未纳入 `AiConcurrencyGuard`** | 纳入闸门 + 独立指标 |
| S-05 | 缓存命中路径 | 缓存命中**仍计入 AI 调用指标** → 统计失真 | 命中不计入 |
| S-06 | `InterviewSessionService` | 无评分数据时平均分取 **0.0** 而非 `null` → 用户看到"0 分" | 置 `null` 并在前端显示"暂无" |
| S-07 | `InterviewSessionService.questionSummary` | **全量加载后内存聚合**，而智能体**每轮对话都调用** | 改 JPQL 聚合或加短缓存 |

**P3**

| 编号 | 问题 |
|---|---|
| S-08 | 错题阈值硬编码、结果未排序 |
| S-09 | 岗位刷新锁为**单实例**语义，多实例失效 |

**✅ 已确认无问题的方面**（避免误报）：IDOR 边界、Admin 鉴权、`AutoKnowledgeService` 并发治理、`InterviewService` 并发闸门覆盖 —— 均正确，**无新增越权风险**。

### 批次 1｜`ai/`（5 文件）+ `config/`（17 文件）+ 部署链（`render.yaml`、`Dockerfile`、`vercel.json`、`application*.yml`、`schema.sql`、CI/keepalive）

**P1**

| 编号 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| A-01 | `config/AiConfig.java:398` | **Embedding 维度未透传**（与 F-C 同源，独立复核确认）：`OpenAiEmbeddingOptions.builder().model(...).build()` 缺 `.dimensions(embeddingDimensions)`；`AI_EMBEDDING_DIMENSIONS` 仅用于 pgvector 建列与日志告警 | 对支持 `dimensions` 的模型补 `.dimensions(...)`；不支持的须保证配置值 = 模型原生值并加启动校验 |
| A-02 | `service/RagSearchService.java:90-91` + `render.yaml:87-88` | **RAG 自动补充阈值未重标定**：判定线 `0.45` 标定自本地 `bge-small-zh-v1.5`，线上 embedding 已换阿里 `text-embedding-v4`，**余弦距离尺度不同** → 自动补充要么误触发、要么永不触发（`render.yaml` 注释自己已警告过） | 按线上模型重新标定（日志 `bestDistance` 法，`render.yaml` 注释已给步骤） |

**P2**

| 编号 | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| A-03 | `config/AiConfig.java:184,91-92` | **诊断拦截器可能失效**：`withDiagnostics` 在 `builder.clone()` 后挂拦截器，第 184 行又 `.clone()` 一次，且 `OpenAiApi` 内部还会再 clone（`clone()` 不继承拦截器）→ 若属实，`AiResponseDiagnosticInterceptor` 对真实请求不生效，「密钥失效可观测」目标落空 | **待验证**：确认 Spring 6.1.21 `clone()` 是否复制拦截器；或保证在 `OpenAiApi` 构造前最后一次 clone 之后再挂载 |
| A-04 | 生产 profile（`application-prod.yml` / `SchemaInitializer`） | **生产 schema 漂移**：未设 `spring.sql.init.mode=always`，`schema.sql`（含 `CREATE EXTENSION vector`、`knowledge_doc` 种子）不在 PostgreSQL 执行；`SchemaInitializer` 也不建 vector 扩展 → 依赖人工在 Supabase 手建，否则 pgvector 建表失败 → **启动失败** | 在 `SchemaInitializer` 内执行 `CREATE EXTENSION IF NOT EXISTS vector` |
| A-05 | `config/SchemaInitializer.java` | 表结构仅靠 `schema.sql` + `DDL_LIST` 手工维护，**存量表 alter 无迁移历史**（B-25） | 引入 Flyway/Liquibase 管理增量迁移 |

**P3**

| 编号 | 位置 | 问题 |
|---|---|---|
| A-06 | `Dockerfile:35` | `-XX:MaxRAMPercentage=50.0` 与显式 `-Xmx220m` 冗余（`Xmx` 优先生效，前者被忽略）；512MB 下 220m 堆 + 128m Metaspace 偏紧 |
| A-07 | `ai/PersistentSimpleVectorStore` + `config/VectorStoreConfig.java:30` | 默认快照路径**硬编码 Windows `D:/xm/data/vectorstore.json`** → 非 Windows 环境与测试隔离依赖外部注入 |
| A-08 | `service/AutoKnowledgeService.java:181` | 自动补充触发时把**用户原始提问明文写日志**（可能含粘贴的简历等 PII） |
| A-09 | `schema.sql:160-166` | `knowledge_doc` 为**死数据**：预置 5 行但全项目无代码读取；`ON CONFLICT` 无冲突目标 |

**✅ 已确认无问题**：`FallbackChatModel` 空响应降级、`AiConcurrencyGuard` 超时信号量、`VersionPathRewriteInterceptor` 正则与按需挂载、Redis/Upstash TLS 回退、`ObjectProvider` 破环、`AiKeySanitizerPostProcessor` 密钥清理与日志打码。

### 已独立验证、证据链完整的发现

见第三节：**F-A**（测试数据源未隔离，P2）、**F-B**（本地 JaCoCo 门槛静默失效，P2）、**F-C**（embedding dimensions 未透传，P1）。

---

## 六、与上轮 75 项发现的对照

### 已核实（批次 3 覆盖的条目）

| 上轮编号 | 结论 | 依据 |
|---|---|---|
| B-01 业务错误 HTTP 状态恒 200 | **仍存** | `common/Result.java:22-28` |
| B-03 `InterviewEventController` status 白名单 + 字段长度校验 | **仍存** | `InterviewEventController.java:60-72,74` |
| B-06 删除会话静默成功 | **仍存** | `AgentController.java:260-263` + `AgentService.java:372-378` |
| B-07 `StatsController` trend dimension 白名单 | **仍存**（无注入面） | `StatsController.java:155` |
| B-08 Map 值强转 | **仍存** | `InterviewController.java:102-103` 等 |
| B-12 作答附图拒绝 svg + magic bytes | **仍存** | `InterviewController.java:202-238` |
| B-20 LIKE 转义对齐 | **部分仍存**（`listUsers` 未转义，与 `listJobs` 自相矛盾） | `AdminService.java:128` / `:83` |
| B-22 `JobFavoriteEntity` 冗余索引 | **仍存** | `JobFavoriteEntity.java:33-35` |
| B-09 / B-10 限流多实例语义 | **仍存**（全部限流仍为单实例内存计数） | `RateLimitInterceptor.java:34` 等 |
| B-17 `InterviewSessionService` 归属校验 | **仍存** | 服务层批次（controller 侧已有补偿） |
| B-21 `questionSummary` 内存聚合 | **仍存** | 服务层批次 |
| B-27 平均分缺数据取 0.0 | **仍存** | 服务层批次 |
| B-04 / B-05 最近问题/错题全量加载后内存 limit | **仍存**（且分页 total 失真） | 服务层批次 |
| B-13 `JobClassifyService` 未纳入闸门 | **仍存** | 服务层批次 |
| B-15 缓存命中计入 AI 指标 | **仍存** | 服务层批次 |
| B-18 岗位刷新未批量化 | **仍存**（N+1 写库） | 服务层批次 |
| B-28 `WebJobSearcherService` 并行化 | **仍存** | 服务层批次 |

### 已核实（批次 1 覆盖的条目）

| 上轮编号 | 结论 | 依据 |
|---|---|---|
| B-11 `application-local.yml` 弱 JWT 默认密钥 | **仍存**（仅 `local` profile；默认值 ≥32 字节可通过 `JwtUtil` 校验；生产 `JWT_SECRET` 必填、缺失即启动失败） | `application-local.yml:84` |
| B-23 `RedisConfig` rediss 默认端口 / 回退 | ✅ **已修**（默认 6380 对齐 Upstash；回退 `127.0.0.1:6379` 为有意容错） | `RedisConfig.java:85` |
| B-24 `application-prod.yml` Hikari 池 2 + connection-test-query | **仍存**（池 2 为免费层有意为之；`connection-test-query` 对 PG 属反模式，建议删掉改用 JDBC4 `isValid`） | `application-prod.yml:30-37` |
| B-25 无 Flyway/Liquibase | **仍存**（升格为 A-05 / P2） | `SchemaInitializer.java` |
| B-26 默认 profile 静默故障 | **仍存**（但为**显式报错**而非静默：默认 profile 用 PostgreSQL + `ddl-auto=none`，无 PG 时启动失败） | `application.yml` |

> **结论**：上轮 P3 backlog **基本未消化** —— 批次 1/2/3 共同覆盖的条目中，**仅 B-23 一条已修**，
> 其余（B-01/03/04/05/06/07/08/09/10/11/12/13/15/17/18/20/21/22/24/25/26/27/28）**全部仍存或部分仍存**。
> 这说明上轮「P3 入 backlog」的条目在本轮**自动升级为待办主力**：它们不是"可忽略建议"，而是**长期未动的技术债**，
> 本轮建议按「是否影响正确性/性能/安全」重新定级 —— 事实上其中 **4 条已被本轮判为 P1**
> （限流多实例、service 归属校验、分页 total 失真、N+1 写库），另 **B-25 升格为 P2**。

## 七、级别统计（阶段一全部批次已合并）

| 批次 | P0 | P1 | P2 | P3 |
|---|---|---|---|---|
| 独立验证（F-A / F-B） | 0 | 0 | 2 | 0 |
| 批次 1 · ai / config / 部署链（A-01~A-09） | 0 | 2 | 3 | 4 |
| 批次 2 · service 业务层（S-01~S-09） | 0 | 3 | 4 | 2 |
| 批次 3 · 接口 / 安全 / 数据层 | 0 | 3 | 9 | 4 |
| 前端（19 视图 + 7 组件 + 24 工具） | 0 | 1 | 5 | 6 |
| **合计（已去重：A-01 与 F-C 为同一问题）** | **0** | **9** | **23** | **16** |

**P1 清单（9 条，按模板须 100% 修复）**

| # | 编号 | 批次 | 摘要 | 涉及文件 |
|---|---|---|---|---|
| 1 | F-C / A-01 | 独立+1 | embedding `dimensions` 未透传，配置项名不副实 | `config/AiConfig.java:395-399` |
| 2 | A-02 | 1 | RAG 自动补充阈值 0.45 未按线上模型重标定 | `service/RagSearchService.java:90-91`、`render.yaml:87-88` |
| 3 | — | 3 | 作答附图仅校验 contentType，放行 `image/svg+xml` 且无 magic bytes | `controller/InterviewController.java:202-238` |
| 4 | — | 3 | 登出无 token 吊销，JWT 24h 内被盗无法失效 | `controller/AuthController.java:235-238`、`security/JwtUtil.java` |
| 5 | — | 3 | 全部限流为单实例内存计数 → 登录锁定可被分摊绕过 | `interceptor/RateLimitInterceptor.java:34` 等 |
| 6 | S-01 | 2 | `InterviewSessionService` service 层缺归属校验 | `service/InterviewSessionService.java` |
| 7 | S-02 | 2 | recent/wrong-questions 全量加载后内存 limit，分页 `total` 失真 | `KnowledgeController` + `InterviewSessionService` |
| 8 | S-03 | 2 | `JobAgentService` upsert N+1 写库 | `service/JobAgentService.java` |
| 9 | — | 前端 | `reportShare.ts` 覆盖率 23.25% / 17.1%，用户可见功能无回归防线 | `frontend/src/utils/reportShare.ts` |

> **P1 中有 4 条（#5/#6/#7/#8）直接源自上轮 P3 backlog 未消化** —— 印证第六节结论。

### 关于 #5（限流多实例语义）的定级说明

该项技术与上轮 B-09/B-10 同源。**生产为 Render 免费层单实例**，故当前实际暴露为 0，
属**潜伏风险**（扩容即触发）。阶段二处理时按「本阶段是否改造架构」决策：
若不做，须在报告中降级为 **P2** 并显式标注「触发条件：横向扩容 / 多实例部署」。**不虚报为已修**。

---

**报告版本**：v2（阶段一全部批次已合并）　**编制**：2026-09-20

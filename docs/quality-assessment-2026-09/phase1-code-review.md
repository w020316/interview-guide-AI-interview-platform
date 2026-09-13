# 阶段一：代码审查报告 — AI 智能面试辅助平台（interview-guide）

> **评估版本**：v1.32.0（commit 2072241，与 origin/main 同步）
> **评估日期**：2026-09-13
> **审查方式**：静态工具（mvn 编译/测试、vue-tsc、vite build、全仓密钥 grep）+ 分模块人工审查（安全配置、Controller 鉴权、Service 业务、数据层、前端全量）+ 主审交叉核实 P0/P1
> **审查范围**：后端 91 个 Java 主源文件 + 前端 73 个 src 文件 + 全部配置/部署文件

---

## 一、质量基线（本轮实测建立）

| 指标 | 实测值 | 状态 |
|---|---|---|
| 后端单元测试 | **337 / 337 通过**（0 失败、0 错误、0 跳过），BUILD SUCCESS，耗时 1:00 | 已实测 |
| 前端单元测试 | **237 / 237 通过**（23 个测试文件），耗时 33.78s | 已实测 |
| 前端类型检查 | `vue-tsc --noEmit` **0 错误** | 已实测 |
| 前端生产构建 | `vite build` **成功**，8.46s；最大分包 element-plus 982.65 kB（gzip 315.48 kB） | 已实测 |
| 测试覆盖率 | 待基线（阶段二测量，前端 vitest 已配置 80% 阈值） | 待基线 |
| 线上性能数据 | 待基线（阶段三/四实测） | 待基线 |

---

## 二、问题统计

| 级别 | 数量 | 定义 |
|---|---|---|
| **P0 阻断** | **2** | 可直接利用的安全漏洞或必然发生的功能故障 |
| **P1 严重** | **11** | 严重安全/数据正确性/资源泄漏风险 |
| **P2 一般** | **25** | 一般缺陷或明显性能/可用性隐患 |
| **P3 建议** | **37** | 编码规范/可维护性/语义一致性建议 |
| 合计 | **75** | — |

核实状态说明：标注「已复核」的条目由主审亲自 Read 核实；其余条目行号均由模块审查用 Read/grep 核实。

---

## 三、P0 阻断（2 条，必须修复）

### P0-01 管理员用户名可被任意抢注，直接获得 ROLE_ADMIN【已复核】

- **位置**：`backend/src/main/resources/application.yml:98`；`backend/src/main/java/com/example/interview/security/JwtUtil.java:79`；`backend/src/main/java/com/example/interview/controller/AuthController.java:91-137`
- **问题**：管理员身份由「用户名是否命中 `app.admin-usernames` 名单」决定，而注册接口只校验长度/字符/重名，**没有管理员名单保留校验**；且默认名单内置 `小吴同学`（`admin-usernames: ${APP_ADMIN_USERNAMES:小吴同学}`），种子管理员默认不创建（`SEED_ADMIN_USERNAME:` 为空）。任何新部署实例上，先注册「小吴同学」的用户即获得 ROLE_ADMIN，可调用全部 `/api/admin/**`（封禁用户、管理岗位、查看指标）。yml 注释「默认空=所有人均为普通用户」与实际默认值自相矛盾。
- **证据**：
  ```yaml
  admin-usernames: ${APP_ADMIN_USERNAMES:小吴同学}
  ```
  ```java
  boolean admin = username != null && adminUsernameSet.contains(username);
  ```
  register 全流程无任何 admin-usernames 命中检查（已逐行核实）。
- **建议**：① 注册时拒绝命中 `app.admin-usernames` 的用户名（返回「用户名不可用」）；② 生产显式配置 `APP_ADMIN_USERNAMES` 并保证账号先于他人注册存在；③ 长期改为数据库 role 字段，用户名名单仅作兼容。
- **复核状态**：主审逐行核实 ✅

### P0-02 Agnes AI API Key 明文提交进 git 并已推送 GitHub【已复核】

- **位置**：`HANDOVER.md:45`、`HANDOVER.md:249`、`HANDOVER.md:261`（共 3 处，全仓 grep 核实无其他真实密钥）
- **问题**：真实 API Key（`sk-Vr4y...NZidC`）明文入库并已推送远程仓库，任何可读仓库者可盗用额度/产生费用。属敏感信息泄露。
- **建议**：① **立即在 Agnes 平台吊销并轮换该 Key**；② HANDOVER.md 中改用占位说明；③ 视仓库公开程度决定是否用 git filter-repo/BFG 清洗历史（清洗涉及 force push，需项目所有者确认后执行）。
- **复核状态**：主审 grep 核实 3 处 ✅

---

## 四、P1 严重（11 条）

### P1-01 答题 SSE 端点：无每用户并发上限 + 绕过 AI 全局闸门【已复核】

- **位置**：`backend/.../controller/InterviewController.java:74-81, 274, 337`
- **问题**：SSE 流式答题只有全局 20 许可信号量（`app.sse.max-concurrent:20`），无按用户维度限制；且直接 `chatClient.prompt().stream()`，绕过其他 AI 路径统一使用的 `AiConcurrencyGuard`。单个登录用户用约 20 个长连接（每连接至多 120s）即可占满全站 SSE 槽位，使所有用户的流式问答不可用，并在无闸门保护下消耗 AI 额度。
- **建议**：增加每用户并发上限（如每用户 1-2 个许可的 Map<String,Semaphore>）；流式调用纳入统一 AI 并发保护。
- **复核状态**：主审核实信号量与调用点 ✅

### P1-02 智能体 SSE 端点：同样无每用户并发上限

- **位置**：`backend/.../controller/AgentController.java:93-101`；`backend/.../service/agent/AgentService.java:78, 325-331`
- **问题**：智能体流式对话仅受 AgentService 内全局 20 许可信号量保护，任一注册用户可持续占满全部槽位（每会话至多 180s），拒绝其他用户服务。
- **建议**：与 P1-01 相同，按用户限制并发 SSE 连接。

### P1-03 AI 并发闸门无超时：许可可能被永久占用，全站 AI 功能瘫痪【已复核】

- **位置**：`backend/.../ai/AiConcurrencyGuard.java:26-38`；`backend/.../config/AiConfig.java:51-54`
- **问题**：`SEMAPHORE.acquire()` 无限等待，许可持有时长取决于 AI 调用本身；而 `OpenAiApi.builder()` 未定制任何 connect/response 超时。一旦某 AI 网关建立连接后挂起不返回，5 个许可被永久占住，出题/评估/RAG/智能体/岗位分类全部排队瘫痪，只能重启恢复。
- **建议**：改为 `tryAcquire(10, TimeUnit.SECONDS)` 超时快速失败；为 AI delegate 显式配置 response/connect 超时（如 60s）。待确认项：Spring AI 1.0 默认 RestClient 是否自带响应超时（阶段二修复时验证）。
- **复核状态**：主审核实整文件与 AiConfig 无超时配置 ✅

### P1-04 向量库内存无界增长：3 条入库路径绕过 500 条容量上限【已复核】

- **位置**：`backend/.../service/ResumeAnalysisService.java:159-175`；`backend/.../controller/KnowledgeController.java:78-98`；`backend/.../service/RagSearchService.java:73-77`
- **问题**：生产向量库为全内存 SimpleVectorStore（512MB 容器、`-Xmx220m`），防 OOM 的 `maxDocuments=500` 计数只覆盖 `importKnowledge` 一条路径；每次简历分析（UUID 随机 id、无去重无上限）与 `batchImport`（单次 100 块 × 8KB）直接 `vectorStore.add()` 绕过计数，重复使用可控耗尽堆内存导致整站 OOM；已删除/历史简历永久驻留可被检索。两个模块审查独立发现，交叉印证。
- **建议**：三条入库路径收敛到统一入口共享 storedDocs 计数；简历向量按 resumeId upsert 覆盖而非新增。
- **复核状态**：主审核实 resume 路径 ✅

### P1-05 ResumeEntity JSONB 列定义导致 H2（local profile）简历表建表失败

- **位置**：`backend/.../entity/ResumeEntity.java:49-50`；`backend/src/main/resources/application-local.yml:9-13`
- **问题**：`@Column(columnDefinition = "JSONB")` 在 H2 上 DDL 失败（审查时用 Hibernate 6.5.3 + H2 2.2.224 实测复现 `Unknown data type: "JSONB"`），且失败仅 WARN 不阻断启动——local profile（H2 内存库）下 resume 表不会被创建，简历保存/列表/详情必然抛「Table RESUME not found」；冒烟测试只启动上下文所以仍然绿灯，形成假信心。
- **建议**：改用 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 6 跨方言 JSON 映射）；冒烟测试补一条 resume 仓库读写断言。
- **复核状态**：模块审查实测复现 + 主审核实 local profile 确为 H2 ✅

### P1-06 favorite_question 无唯一约束 + toggle 先查后写：重复收藏后接口永久 500

- **位置**：`backend/.../entity/FavoriteQuestionEntity.java:22-23`；`backend/.../service/FavoriteService.java:60-75`；`backend/.../repository/FavoriteQuestionRepository.java:20`
- **问题**：表对 `(user_id, question_id)` 无唯一约束（对照 JobFavoriteEntity 有），toggle 为 check-then-act，并发双击/双端产生重复收藏；此后 `findByUserIdAndQuestionId`（单行派生查询）遇多行抛 `IncorrectResultSizeDataAccessException`，该题的收藏切换接口永久 500，用户无法自行恢复。
- **建议**：加 `@UniqueConstraint(user_id, question_id)`（配套生产迁移脚本）；findBy 改返回 List 取首条删除；插入捕获 DataIntegrityViolationException 返回「已收藏」。

### P1-07 Redis 连接串（含密码）随 WARN 日志泄露

- **位置**：`backend/.../config/RedisConfig.java:105-107`
- **问题**：`REDIS_URL` 解析失败时把完整连接串（`rediss://default:<PASSWORD>@host...`）打进日志，Upstash 凭据随日志平台长期留存。
- **建议**：打印前对凭据打码（`url.replaceAll("//[^@]*@", "//***@")`）。

### P1-08 SSRF 残余面：evaluate 图片 URL 的重定向/DNS 重绑定【待确认】

- **位置**：`backend/.../service/InterviewService.java:277`；`backend/.../util/SsrUrlValidator.java:86-101`
- **问题**：imageUrl 经一次性校验后由 Spring AI 在服务端二次解析 DNS 抓取——存在 DNS 重绑定 TOCTOU 窗口；若媒体下载器跟随 302，可用「公网 URL → 302 → http://169.254.169.254/」打云元数据。对照：ResumeController 的 Jsoup 抓取已显式禁重定向（该路径已封死），本路径未封。
- **建议**：阶段二先验证 Spring AI Media 下载行为（受控 302 复现），修复为禁重定向 + 校验时绑定 IP，或 imageUrl 限本系统 Supabase 域名白名单。

### P1-09 SsrUrlValidator 内网黑名单缺 IPv6 ULA 与 CGNAT 段

- **位置**：`backend/.../util/SsrUrlValidator.java:127-134`
- **问题**：黑名单遗漏 `fd00::/8`（IPv6 ULA，Java `isSiteLocalAddress` 只识别 fec0::/10）与 `100.64.0.0/10`（CGNAT，含阿里云元数据 100.100.100.200），在对应环境部署时内网/元数据接口可通过校验。
- **建议**：按地址字节前缀补充黑名单判断 + 增加阿里云元数据地址。

### P1-10 前端「最新一次」得分实为历史最高分【已复核】

- **位置**：`frontend/src/utils/scoreTrend.ts:64-77`；消费方 `frontend/src/views/ProgressView.vue:26-27`
- **问题**：`latest` 取自升序排序数组的末位（=max），不是时序最新；同函数 `delta` 却用未排序数组，口径自相矛盾。ProgressView「最新一次」统计卡只要最近一次不是历史最高就必错。现有测试用递增数据（70/80/90）恰好掩盖了该 bug。
- **建议**：`latest` 改取 `scores[scores.length - 1]`；补降序用例（[90,80,70] 断言 latest===70）。
- **复核状态**：主审核实 ✅

### P1-11 AI 长请求被客户端 120s 超时后静默重放，后端双份生成

- **位置**：`frontend/src/api/index.ts:154-169`；关联 `frontend/src/views/InterviewView.vue:74, 754-756`
- **问题**：axios 拦截器对 `ECONNABORTED`（本地超时）也触发「冷启动唤醒 + 重放原请求」，而 UI 自述 AI 出题「通常需要 2-3 分钟」> AI_TIMEOUT=120s。出题一旦超过 120s 即被本地掐断并**静默重放**：后端可能同时跑两份 AI 生成（额度翻倍），用户无提示等待可达 120s+唤醒+120s。
- **建议**：重放条件剔除 AI 类长耗时 POST 的 `ECONNABORTED`（仅保留网络错误类）；出题接口 timeout 与 UI 宣称的时长对齐（≥180s）或改为提交后轮询。

---

## 五、P2 一般（25 条）

### 安全（6）

| # | 位置 | 问题与建议 |
|---|---|---|
| P2-01 | `SecurityConfig.java:70-74`；prod 配置无 springdoc 开关 | Swagger/OpenAPI **生产匿名可访问**，完整暴露接口结构，降低侦察成本。建议 prod 禁用 springdoc 或纳入认证。 |
| P2-02 | `AdminSeedInitializer.java:53-59` | 每次启动**强制重置**管理员密码为环境变量值——管理员改掉的密码在每次 Render 自动部署重启后被静默还原。建议改为仅创建时设置。 |
| P2-03 | `JwtAuthFilter.java:44-48`；`service/UserBanRegistry.java` | 用户封禁仅存进程内 Map，重启/重新部署即全部解禁；被封禁用户 JWT 仍有效 24h 无服务端吊销。建议封禁落库 + Filter 查库（可短 TTL 缓存）。 |
| P2-04 | `WebMvcConfig.java:22-29`；`AuthController.java:117,124` | 注册接口完全无限流（限流拦截器整体豁免 `/api/auth/**`），可脚本批量注册；「用户名已存在/邮箱已被注册」回显可用于枚举。建议对 register 单独限流（如 5 次/小时/IP）。 |
| P2-05 | `backend/pom.xml:10`（Spring Boot 3.3.6 / 内嵌 Tomcat 10.1.33） | **已联网核实**：Spring Boot 3.3.x OSS 支持已于 2025-06 结束（3.3.11 为最后批次补丁之一）；Tomcat 10.1 存在 multipart 内存消耗类 CVE（修复于 10.1.42+/10.1.50+）。建议升级 Spring Boot 3.3.13+/3.5.x 线。来源见文末。 |
| P2-06 | `service/SupabaseStorageService.java:54-56, 74-81` | 简历/作答附图（含 PII）写入**公读 bucket 的永久公开 URL**，泄露即永久可读，无过期/签名机制。建议改私读 bucket + 签名 URL。 |

### Controller / 鉴权（5）

| # | 位置 | 问题与建议 |
|---|---|---|
| P2-07 | `AuthController.java:220-252`；`SecurityConfig.java:67`；`JwtAuthFilter.java:44-48` | `GET /api/auth/me` 落在 `/api/auth/**` permitAll 内，匿名请求返回 200 假身份；**被禁用用户**持有效 token 时返回 `banned:false`，与该接口设计目的直接矛盾。建议 `/api/auth/me` 单列 authenticated。 |
| P2-08 | `HealthController.java:55-64`；`WebMvcConfig.java:27` | 匿名深度体检端点执行 DB SELECT 1 + Redis PING 并暴露 JVM 堆/线程/uptime——无认证 DoS 放大点 + 基础设施信息泄露。建议匿名仅保留轻量探活。 |
| P2-09 | `InterviewSessionController.java:113-141` | 批量保存题目**无条数与单题长度上限**，单请求可灌任意多条题目行。建议 ≤50 条/请求 + 字段长度校验。 |
| P2-10 | `RateLimitInterceptor.java:44-51`【待确认】 | 全局 IP 限流 10 次/分钟对 NAT 后多用户过严（正常浏览即可能 429），可用性缺陷。建议提额或 IP+userId 双层模型；先在生产观察 429 比例。 |
| P2-11 | `AuthController.java:92` 等 @RequestBody 端点【待确认】 | 应用层对 JSON 请求体无大小上限（仅 multipart 有 10MB），超大 JSON 可致内存压力；可利用性取决于外层代理 body 上限。建议代理层限 1MB + 关键端点 Content-Length 预检。 |

### Service / 业务（4）

| # | 位置 | 问题与建议 |
|---|---|---|
| P2-12 | `RagSearchService.java:217-229` | RAG 去重限流计数器只在「非重复」分支自增，重复率高的批次完全失去「每批最多 50 次」保护，仍产生全额 embedding 调用。把自增移入条件内。 |
| P2-13 | `service/job/JobClassifyService.java:97-119` + `JobAgentService.java:131-136` | AI 分类按**数组出现顺序**与岗位配对，忽略 AI 已输出的 index 字段；模型乱序/跳号即错配到其他岗位。按 index 放入定长数组后再返回。 |
| P2-14 | `InterviewService.java:88-105` | 面试题生成的向量检索**无 userId 过滤**（对照 RagSearchService 有），会把任意用户的简历/知识文档拼入当前用户 prompt；当前因全环境 SimpleVectorStore + isVectorStoreAvailable()=false 处于死分支，**一旦切回 pgvector 立即成为跨用户数据泄露**。补 filterExpression 或删除死分支。 |
| P2-15 | `service/agent/AgentService.java:337-340, 124-131` | 对外的 `cancel(AtomicBoolean)` 全工程无调用点且 executeLoop 从不读取——客户端断连后 ReAct 循环照常跑满 8 轮 AI 调用（浪费额度）；heartbeat 定时任务在超时窗口下可永久残留。把取消标志接入循环 + emitter 生命周期内取消 heartbeat。 |

### 数据层（5）

| # | 位置 | 问题与建议 |
|---|---|---|
| P2-16 | `service/agent/AgentService.java:445-459` | 保存新消息不更新会话 `updated_at`（@UpdateTimestamp 只在会话实体自身变更时触发），会话列表按 updated_at 倒序实际等于创建顺序，与展示语义不符。保存消息后 bump 会话。 |
| P2-17 | `config/ProdVectorStoreConfig.java:18-27`；prod 排除 pgvector 自动配置 | pgvector 全链路为**死配置**：任何环境都用内存 SimpleVectorStore，用户知识与简历向量**每次部署/重启全部丢失**，pgvector 依赖白打镜像。二选一：恢复 pgvector（1536 维配置已正确）或删除死配置并固化「重启丢知识」产品语义。 |
| P2-18 | `RagSearchService.java:97-105`；`schema.sql:160-167` | 检索过滤 `shared="true"` 为永假死分支（全工程无写入点）；knowledge_doc 表及其种子数据无任何 Java 代码读取，且 PG 下 spring.sql.init 默认不执行——预置共享知识功能不存在。补 seeding 或删除死代码。 |
| P2-19 | `service/JobFavoriteService.java:49-73` | 岗位收藏并发双击撞唯一约束后被 DataAccessException 处理器转成 500「数据库操作失败」，应为幂等「已收藏」。捕获 DataIntegrityViolationException 重查返回当前状态。 |
| P2-20 | `entity/InterviewQuestionEntity.java:20-69` 等 | 全部实体无 `@Version` 乐观锁，同一题并发答题 last-write-wins 静默覆盖先提交的回答与评分；FINISHED 会话仍可被改写。加版本字段 + 冲突转 409 + 校验会话状态。 |

### 前端（5）

| # | 位置 | 问题与建议 |
|---|---|---|
| P2-21 | `views/AdminView.vue:164-170` | `setInterval` 从不清理，离开页面仍每 30s 请求；登出后触发 401 → 全页跳转。onUnmounted 清理。 |
| P2-22 | `views/InterviewView.vue:813-897` | AI 提示流冷启动重试路径中，外层 60s 兜底定时器未提前清除，会在重试流建立后误 abort 新 controller，流被静默掐断。递归前 clearTimeout 或超时与单次 fetch 绑定。 |
| P2-23 | `src/auth.ts:42-45, 71-84` | JWT 存 localStorage，XSS 一旦发生即可窃取 24h 凭证，DOMPurify 成为唯一防线无纵深；无 exp 的 token 被视为永久有效。中期迁 httpOnly Cookie；短期对无 exp token 视为无效。 |
| P2-24 | `views/AgentView.vue:390-667`；`views/JobsView.vue:541-741` | 大量引用**从未定义**的 CSS 变量（--card-bg/--border-color 等，全仓无定义），全部落入硬编码浅色 fallback——**暗色主题下这两页仍白底**，且 fallback 靛蓝色偏离品牌色。统一替换为 variables.css 语义变量。 |
| P2-25 | `views/AdminView.vue:306-345` | 管理后台整页硬编码浅色（#fff/#2f6fed 等），暗色模式完全割裂。改用设计系统变量。 |

---

## 六、P3 建议（37 条，入 backlog）

**后端（23）**

1. `common/Result.java:22-24` + `GlobalExceptionHandler.java:79-84` — 业务错误 HTTP 状态恒 200、BusinessException→500，违反 REST 语义并污染 5xx 告警。
2. `GlobalExceptionHandler.java:30-34` — IllegalArgumentException message 原样回显（含输入回显与资源探测面）。
3. `controller/InterviewEventController.java:57-77` — 日志 status 无白名单、字段无长度校验（超 varchar(255) 变 500）。
4. `controller/KnowledgeController.java:60-69` — `/import` 限 100 条但单条无 8KB 截断（与 /import/batch 不一致）。
5. `controller/KnowledgeController.java:170-196` — recent-questions/wrong-questions 全量加载后内存 limit，应仓储层分页。
6. `controller/AgentController.java:234-240` — 删除他人/不存在会话静默 200。
7. `controller/StatsController.java:155-163` — trend dimension 非法值静默按 WEEK 处理。
8. `controller/InterviewController.java:99-100` 等 — Map 值强转 (String)，类型不符落 500 而非 400。
9. `interceptor/RateLimitInterceptor.java` + `util/ClientIpUtil.java` — 限流为单实例内存实现，多实例限额翻倍、重启清零（XFF 解析方向本身正确）。
10. `util/PerUserRateLimiter.java:52-55, 71-80` — 键数达 10000 上限后活跃键仍可无限增长。
11. `application-local.yml:73-77` — local 弱 JWT 默认密钥，误用 profile 即可伪造管理员 token。
12. `controller/InterviewController.java:186-217` — 作答附图仅校验声明 Content-Type 前缀，`image/svg+xml` 可入库（存储域 XSS 面）；建议魔数校验 + 拒绝 SVG。
13. `service/job/JobClassifyService.java:85-87` — 全工程唯一绕过 AiConcurrencyGuard 的 AI 调用且无指标。
14. `service/agent/AgentService.java:209-226` — callWithRetry 用 IllegalStateException 判「闸门中断不重试」，与 FallbackChatModel 全链失败异常同类型，最需重试的路径永不重试。
15. `service/InterviewService.java:159-165`、`ResumeAnalysisService.java:149-152` — 缓存命中也计入 AI 调用指标，统计失真。
16. `service/InterviewService.java:73-74` — 缓存 key 拼接无分隔符，("ab","c") 与 ("a","bc") 同 key。
17. `service/InterviewSessionService.java:72-95` — finishSession/saveQuestions 无 service 层归属校验（防线仅在 Controller）。
18. `service/job/JobAgentService.java:139-204` — 刷新逐条 findBy+save，无批量。
19. `service/job/JobMatchService.java:62-71` — 循环内重复 toLowerCase；"es"/"ts" 子串误配（process/notes 命中）。
20. `service/job/JobAgentService.java:239-241` + `AdminService.java:126-132` — location/用户名搜索 LIKE 通配符未转义（keyword 路径已正确）。
21. `service/InterviewSessionService.java:133-142` — questionSummary 全量加载题目实体仅取统计值，且智能体每轮对话都调用；应改 JPQL 聚合或缓存。
22. `entity/JobFavoriteEntity.java:33-35` — idx_job_favorite_user 与唯一约束左前缀完全重复，冗余索引。
23. `config/RedisConfig.java:85, 105-110` — rediss 默认端口误为 6380（主流 6379）；初始化失败静默回退 127.0.0.1（生产必失败仅一条启动日志可查）；无 connect timeout。

24. `application-prod.yml:29-38` — Hikari maximum-pool-size:2 配 50 线程过小；connection-test-query 属旧式做法。
25. `config/SchemaInitializer.java:122-138` — 只建新表不做 alter，生产无 Flyway/Liquibase，加列变更将运行时才暴露。
26. `application.yml:16-18` — 默认 profile（无 profile 启动）指 Postgres + ddl-auto:none 且不执行 schema.sql，多数接口静默报错。
27. `service/InterviewSessionService.java:181-185, 225-229` — 无评分数据时平均分 orElse(0.0)，与「真实 0 分」混淆。
28. `service/job/WebJobSearcherService.java:63-80` — 4 个候选源串行抓取（注释称并行），最坏 32s 占住智能体线程。

**前端（9）**

29. `views/ResumeHistoryView.vue:324-346`、`views/InterviewView.vue:594-600` — scoreColor/scoreGradient 本地重复实现，与 utils/score.ts 并存。
30. `views/AdminView.vue:159-162` — 本地 getErr 与 api 层 getErrMessage 语义不一致。
31. `utils/format.ts:1-32` — 整模块死代码（无任何 import）。
32. `utils/weakCategories.ts:26-33` — 分类均分分母含无分错题，薄弱分类排序失真。
33. `theme.ts:37-43` — localStorage.setItem 未 try-catch（隐私模式抛异常）。
34. `views/AgentView.vue:13-22` — 会话项无 role/tabindex/键盘事件（对比 HistoryView 已做）。
35. `package.json:16` — @types/dompurify 冗余（DOMPurify 3.x 自带类型）且误放 dependencies。
36. `index.html:11` — preconnect 硬编码 Render 域名，与 .env 的 VITE_API_BASE_URL 双处维护。
37. **版本号四源不一致**：`package.json`=1.28.0、`changelog.ts CURRENT_VERSION`=1.32.0、`backend pom.xml`=1.31.3、`application.yml APP_INFO_VERSION`=1.23.0 — 建议固定单一版本来源。

---

## 七、已确认无问题的重点区域（覆盖面证明）

1. **JWT 实现**：HS256 + secret ≥32 字节强制校验、issuer/audience 防跨服务重放、异常分类处理；prod 无弱默认密钥。
2. **CORS**：白名单来自配置的具体域名，无 `*`，allowCredentials 组合安全。
3. **IDOR/水平越权**：session/favorite/agent 会话/calendar/resume 全部有 service 层属主校验；所有 userId 均取自 JWT principal，无端点信任客户端传 userId。
4. **垂直越权**：AdminController 类级 `@PreAuthorize("hasRole('ADMIN')")` + 方法安全启用；ROLE_ADMIN 仅能来自服务端按名单签发的 JWT。
5. **mass assignment**：实体全部逐字段构建，role/userId/isDeleted 无客户端绑定面。
6. **文件上传**：简历上传 10MB + 扩展名/Content-Type 白名单 + PDF 魔数校验；SSRF 校验 + 禁重定向 + 5MB 上限（resume import-url 路径完整）。
7. **XSS（前端）**：全部 6 处 v-html 均经 renderMarkdown（markdown-it html:false + DOMPurify 危险属性/协议禁用）；reportPdf 全部插值经 esc() 转义。
8. **路由守卫（前端）**：18 条路由全部显式 requiresAuth，/admin 双层校验（前端守卫 + 后端方法安全）；redirect 参数有开放重定向防护。
9. **竞态清理（前端）**：SSE fetch 全部在卸载时 abort；切题终止旧流；定时器/语音识别均有清理；主要提交按钮有 loading 防重入。
10. **Redis 序列化与缓存**：JSON 序列化（非 JDK）；写入均带 TTL；读写异常均降级直连 AI，不阻塞主流程。
11. **AI Prompt 注入面**：用户输入（简历/JD/问题/回答）统一经 PromptSanitizer（2000 字符截断 + 注入模式剥离），并有中英文注入测试覆盖。
12. **native SQL 注入**：repository 无 nativeQuery，动态查询 Specification 对 keyword 已转义（location 例外已列 P3-20）。
13. **事务边界**：未发现 @Transactional 内做外部 HTTP/AI 调用；@Modifying 均在事务内。
14. **除零/数值边界（前端）**：进度条/百分比均有 Math.max/min 钳制；日期构造显式本地时区避免 UTC 偏移。

---

## 八、阶段二修复优先序建议

1. **P0 立即修复**（P0-01 注册保留管理员名单、P0-02 吊销轮换 Key + 移除明文）：预计 0.5-1h
2. **P1 全量修复**（11 条）：SSE 每用户限流（P1-01/02）、AI 闸门超时（P1-03）、向量库收敛（P1-04）、JSONB 跨方言（P1-05）、收藏唯一约束（P1-06）、日志打码（P1-07）、SSRF 收紧（P1-08/09）、scoreTrend（P1-10）、重放策略（P1-11）：预计 3-4h
3. **P2 修复 ≥90%**（25 条，其中 3 条「待确认」项先验证再定级）：预计 2-3h
4. 每个修复独立 commit + 全量回归（当前基线：后端 337、前端 237 必须保持全绿）；核心模块覆盖率冲 80%
5. P3（37 条）入 backlog，不阻塞本阶段

---

## 九、参考来源（联网核实）

- Spring Boot 3.3.x OSS 支持结束与 CVE-2025-22235 批次发布：https://spring.io/blog/2025/04/24/spring-boot-CVE-2025-22235
- Apache Tomcat 10 官方漏洞列表（含 multipart 内存消耗类问题）：https://tomcat.apache.org/security-10.html
- Tomcat 10.1.50 修复的 CVE（CVE-2025-66614 / CVE-2026-24733）：https://www.herodevs.com/blog-posts/cve-2025-66614-cve-2026-24733-two-tomcat-vulnerabilities-that-also-affect-spring-boot-2-7

# 执行计划书 — AI 智能面试辅助平台（interview-guide）全面质量评估 · 第二轮

> **计划性质**：待确认（本文件提交后等待放行，未获确认不进入阶段一）
> **计划编制日期**：2026-09-20
> **评估对象**：`interview-guide`（AI 智能面试辅助平台）
> **本轮基准 commit**：`54effbe`（工作区干净，无未提交改动）
> **上一轮评估**：2026-09-13（自述 v1.32.0，产物归档于 `docs/quality-assessment-2026-09/`）
> **本轮产物归档目录**：`docs/quality-assessment-2026-09-20/`

---

## 0. 已确认事项（2026-09-20 确认，计划生效）

| # | 事项 | 结论 |
|---|---|---|
| C-1 | **评估对象** | ✅ 确认为 `interview-guide`（AI 智能面试辅助平台）。工作区另有 `lawai-deploy`（ai-legal-assistant）与根目录 NovaIM，本轮不评估 |
| C-2 | **本轮定位** | ✅ **复评**：基线取上轮（2026-09-13，v1.32.0）结论 + 本轮实测，输出前后对比 |
| C-3 | **阶段范围** | ✅ 执行阶段 0 ~ 阶段五；阶段六（调研立项）暂不执行，按需追加 |
| C-4 | **AI 依赖** | ✅ 允许消耗线上免费 AI 额度用于阶段四评测 |
| C-5 | **推送策略** | ✅ **变更**：所有改动在**验证功能正常后，推送并部署**（覆盖上轮的「仅本地」约定） |

> **【长期规则 · 2026-09-20 生效】**
> 后续所有修改与完善操作，**在确认功能使用正常后，一律 push 并触发部署**。
> 因此每个修复的验收标准增加一条：**本地回归通过 → push → 线上部署生效 → 线上复验通过**。
> 部署链路：`git push` → Render 自动部署（`render.yaml` `autoDeploy: true`）+ Vercel 自动部署。
> 线上复验要点：Render 免费层冷启动实测约 98 秒，健康检查须带重试、超时 > 100 秒；
> 注意 `/api/info` 版本号写死为 `1.0.0`，**不能作为部署生效的判据**，需改用其他确定性业务指纹
> （如新代码引入的响应措辞变化）轮询确认。

---

## 1. 项目上下文理解

### 1.1 项目定位

AI 驱动的求职辅助平台：简历分析 → AI 模拟面试 → RAG 知识库问答 → 回答质量评估 → 学习中心/错题本 → 管理后台。定位为**零成本云端部署的作品集级项目**（求职者自用 + 简历演示）。

### 1.2 技术栈（实测确认）

| 层级 | 技术 | 实测信息 |
|---|---|---|
| 后端 | Spring Boot **3.3.13** + Java **21**（虚拟线程） | `backend/pom.xml` `<version>1.31.3</version>` |
| AI 框架 | Spring AI 1.0.0，OpenAI 协议 | 多厂商降级链 + 可插拔 embedding |
| 数据库 | PostgreSQL 16 + pgvector（生产 Supabase 免费层） | 本地 profile 用 H2 + `PersistentSimpleVectorStore` |
| 缓存 | Redis 7（生产 Upstash，TLS） | 仅用于 AI 响应缓存 |
| 前端 | Vue 3 + Element Plus + TypeScript + Vite 8 | `frontend/package.json` v1.28.0，vitest 5.0.0 |
| 前端托管 | Vercel — `https://interview-guide-ai-interview-platform.vercel.app` | `vercel.json` 将 `/api/*` 反代到 Render |
| 后端容器 | Render 免费层（Singapore，512MB，闲置休眠） | `render.yaml`，healthCheck `/actuator/health` |
| 存储 | Supabase Storage（bucket `resumes`） | 服务端换签 10 分钟签名 URL |
| 保活 | GitHub Actions `keepalive.yml` + CI `ci.yml` | CI：后端 mvn test / 前端 vue-tsc + vitest + audit + build |

### 1.3 代码规模（本轮实测）

| 维度 | 数量 |
|---|---|
| 后端主代码 | **100** 个 `.java` |
| 后端测试代码 | **61** 个测试类 |
| 后端包分布 | service 27 / config 17 / controller 13 / repository 10 / entity 10 / util 9 / ai 5 / security 3 / common 3 / interceptor 1 / dto 1 |
| 前端视图 | **19** 个 View（含 Admin/Agent/Calendar/Jobs/Knowledge/Learning/Profile/Progress/WrongBook 等） |
| 前端组件 | 7 个（Base* 设计系统组件 + ChangelogDialog/FavoriteToggle） |
| 前端测试 | **25** 个 `.test.ts` |
| 前端工具层 | `src/utils/` 24 个模块（SSE、PDF 报告、分享、语音、评分趋势等） |
| Git 提交总数 | **222** |

### 1.4 与上轮评估之间的演进（本轮必须覆盖的新增面）

上轮评估于 2026-09-13 收官（v1.32.0，19 个修复 commit）。其后至今（约 7 天）新增：
1. **RAG 全行业知识自动补充**（`AutoKnowledgeService`）——新业务闭环，含阈值判定与配额安全阀
2. **向量库持久化**（`PersistentSimpleVectorStore`）——本地 profile 原子落盘/恢复/计数同步
3. **Embedding 提供方可插拔** + 硅基流动 bge-m3 → 百炼 `text-embedding-v4` 切换
4. **签名 URL 落地**（P2-06）：服务端换签 + 前端适配
5. **后端覆盖率四批补齐**：43.3% → 85.0%，pom 固化 80% 门槛
6. **保活/唤醒重写**：8 分钟循环重试
7. `render.yaml` 于**当日**修正三处失效配置（AI 降级链、Embedding 通道、自动补充开关）

---

## 2. 质量基线

> 原则：**实测值标注「实测」+ 采集命令；文档值标注「上轮记录」+ 出处；无数据标注「待基线」。**
> 绝不将设计目标或推断值写成实测值。

### 2.1 构建与测试

| 指标 | 上轮记录（2026-09-13） | 本轮实测（2026-09-20） | 来源 |
|---|---|---|---|
| 后端单元测试 | 606/606 全绿 | **672/672 全绿 → BUILD SUCCESS**（1m09s，隔离数据源后实测）<br>⚠️ 未隔离数据源时同一套测试为 `672 例 / 670 通过 / 2 Error → BUILD FAILURE`（1m02s） | `mvn -o -B -ntp test` |
| 前端单元测试 | 241/241 全绿 | **270/270 全绿**（25 个文件，35.76s） | `vitest run --coverage` |
| 后端行覆盖率 | 85.0%（pom 固化 80% BUNDLE 门槛） | **≥80% 达标**（CI run `35489467331` 日志实测：`All coverage checks have been met.`）；<br>⚠️ **本地无法复现**（JaCoCo 因中文路径 fork 失效，见 F-B），精确数值未打印 | pom JaCoCo `check`（BUNDLE LINE ≥ 0.80） |
| CI 状态 | 未记录 | **绿**：HEAD `54effbe` 的 CI 1m9s success；Keepalive 定时任务持续 success | `gh run list` |
| 前端覆盖率（statements） | 85.07% | **85.01%**（Branch 85.22 / Funcs 92.43 / **Lines 86.23**） | `vitest --coverage`（utils/api/auth） |
| 类型检查 | vue-tsc 0 错 | **0 错（EXIT=0）** | `vue-tsc --noEmit` |
| 前端构建 | 6.13s 成功 | **成功，8.30s**（最大 chunk `element-plus` 982.65 kB / gzip 315.48 kB） | `vite build` |
| 依赖安全审计 | 未记录 | **0 vulnerabilities**（须用官方源，见 §2.6） | `npm audit --audit-level=high` |

> ⚠️ **后端红灯是真实基线，不是环境噪音** —— 但根因是**环境 + 测试隔离缺陷共同作用**，非业务代码回归。详见 §2.5。

### 2.2 线上可用性（实测 2026-09-20）

| 端点 | 结果 |
|---|---|
| `GET /actuator/health`（Render） | **HTTP 200，1.24s**（未触发冷启动） |
| `GET /api/info`（Render） | **HTTP 200**，返回 `version: "1.0.0"` |
| 前端（Vercel） | 待阶段三实测 |

### 2.3 版本号一致性（实测：**5 源不一致**，上轮 F-09 未收敛且恶化）

| 来源 | 值 |
|---|---|
| `backend/pom.xml` | `1.31.3` |
| `frontend/package.json` | `1.28.0` |
| `frontend/src/changelog.ts`（最新条目） | `v1.33.3` |
| `/api/info` 线上返回值（实测） | `1.0.0` |
| `application-local.yml` 注释 / `ApplicationContextSmokeTest` javadoc | `v1.34.0` |
| `APP_INFO_VERSION`（application.yml / env） | 待阶段 0 核对 |

> 上轮 F-09 已记录「四源不一致」，本轮实测**至少 5 源不一致**且最大跨度 1.28.0 ↔ 1.34.0 → 上轮该条**未收敛**，需在本轮重新定级（疑升 P2）。前缀是否统一（`v` 前缀 / 无前缀）也需一并规范。

### 2.4 已知未闭环事项（从上轮报告/配置注释继承，本轮需复核状态）

| 事项 | 上轮状态 | 本轮需做 |
|---|---|---|
| **Embedding dimensions 未透传** | 未记录 | `render.yaml` 注释自述：`AiConfig` 构造 embedding 时**未调用 `.dimensions()`**，故 `AI_EMBEDDING_DIMENSIONS` 仅影响建表 → **疑似真实缺陷，需核实并定级** |
| **RAG 自动补充阈值未重标定** | 新增 | `RAG_AUTO_SUPPLEMENT_MAX_DISTANCE=0.45` 系本地 bge-small 实测值；换百炼 v4 后尺度不同 → 需实测标定 |
| Supabase `resumes` bucket 仍公开 | 剩余动作 | 签名 URL 已落地但 bucket 未转私有 → 复核 |
| 业务错误 HTTP 状态恒 200（B-01） | P3 backlog | 复核是否影响监控语义 |
| 限流/封禁为单实例语义（B-09/B-10） | P3 backlog | 复核 |
| `local` profile 弱 JWT 默认密钥（B-11） | P3 backlog | 复核 |
| Schema 无 Flyway，生产结构漂移防线缺口（B-25） | P3 backlog | 复核 |
| 37 条 P3 backlog | 归档 | **逐条复核状态**（已修/仍存/失效），不重复计问题 |
| Safari/Firefox 未实测 | Windows 环境限制 | 尽量用本机可用浏览器；不可行则如实标注 |

### 2.5 基线采集期间已发现的问题（证据链完整，Phase 1 直接立案）

> 这是基线段落的副产物：为取基线而跑全量测试，直接撞出一个真实缺陷。

**F-A｜`ApplicationContextSmokeTest` 未隔离数据源 → 本地全量测试必然红灯 + 污染真实数据**

| 项 | 内容 |
|---|---|
| 证据 | 实测 `mvn -o -B -ntp test` → `Tests run: 672, Failures: 0, Errors: 2` → BUILD FAILURE（1m02s） |
| 报错 | `ApplicationContextSmokeTest.contextLoads` / `.resumeRepositoryReadWriteOnH2` 双双 ERROR：`Unable to open JDBC Connection for DDL execution [IO Exception: "D:/xm/data/interview.mv.db" [90028-224]]`（H2 90028 = 文件被锁） |
| 触发条件 | 本机正运行 `local` profile 开发服务（实测 PID 25588，`-Dspring.profiles.active=local`，占用 8080/8090，持有该 H2 文件） |
| 代码位置 | `backend/src/main/resources/application-local.yml:18` → `jdbc:h2:file:D:/xm/data/interview`（**固定共享文件库**）；`backend/src/test/java/com/example/interview/ApplicationContextSmokeTest.java:38-41` → `@TestPropertySource` **只隔离了向量库快照**（`app.rag.persist-enabled=false` + tmp 快照），**未隔离数据源** |
| 成因 | v1.34.0（2026-09-20，当日）将 local 数据源由 `mem:` 改为**文件库**，但未同步为该测试补数据源隔离 —— 属**当日新引入的缺口** |
| 影响 1 | 只要开发服务在跑，任何本地/自托管全量测试都红 → 掩盖真实回归（狼来了效应） |
| 影响 2 | 即使无冲突，测试会 `saveAndFlush` 写 `smoke-user` 记录进**用户真实本地库**（与 `@TestPropertySource` 注释自称的「测试必须与真实数据隔离」自相矛盾） |
| 拟定级别 | **P2（一般）** — 不影响生产功能，但破坏测试可信度与本地数据 |
| 建议修复 | 测试内追加 `spring.datasource.url=jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1`（或 `@AutoConfigureTestDatabase`），使测试完全脱离 `D:/xm/data` |

**✅ 假设已验证（2026-09-20）**：保持本机开发服务运行不变，仅用环境变量把数据源切到内存库后复测 ——
`SPRING_DATASOURCE_URL=jdbc:h2:mem:smokebaseline;DB_CLOSE_DELAY=-1 mvn -o -B -ntp test`
= **672/672 全绿，BUILD SUCCESS（1m09s）**。
证明那 2 个 Error **确系 H2 文件锁引起的测试隔离缺陷，而非业务代码回归** —— F-A 立案成立，
且「后端测试红灯」这一结论在未隔离数据源前**不可用**。

> 该发现同时印证：**本轮的基线必须以「停掉本机开发服务」或「隔离数据源」为前提复测**，否则后端测试结论不可用。此为阶段 0 的必做动作。

### 2.6 前端类型检查 / 构建 / 依赖审计（实测 2026-09-20）

| 检查 | 结果 | 备注 |
|---|---|---|
| `vue-tsc --noEmit` | **0 错（EXIT=0）** | 与上轮持平 |
| `vite build` | **成功，8.30s** | 较上轮 6.13s 略慢；`element-plus` chunk 982.65 kB（gzip 315.48 kB）为体积大头，属阶段五可优化项 |
| `npm audit --audit-level=high` | **0 vulnerabilities** | ⚠️ 本机 npm 源为 `registry.npmmirror.com`，**未实现 audit 接口**（`NOT_IMPLEMENTED`）；须显式 `--registry=https://registry.npmjs.org` 才能得到真实结果 —— 阶段 0 应把这一条固化进脚本 |

> 原始日志：`D:\xm\baseline_backend_test.log`、`D:\xm\baseline_frontend_test.log`、`D:\xm\baseline_frontend_build.log`

---

## 3. 六阶段总体计划

| 阶段 | 目标 | 预计耗时 | 关键资源 | 预期输出 |
|---|---|---|---|---|
| **阶段 0** 基线复核 | 建立可信基线 | 0.5h | Maven/JDK21/Node22、线上端点 | 基线表（填实报告 §2） |
| **阶段一** 代码审查 | 全量静态+人工审查 | 6–9h | 静态分析工具 + 逐模块人工审查 | `phase1-code-review.md`（标准化问题清单） |
| **阶段二** 问题修复 | P0/P1 100%、P2 ≥90% | 8–14h | 测试套件、独立 commit 纪律 | `phase2-fix-record.md` + N 个 commit |
| **阶段三** 页面/界面检查 | 19 视图 × 5 分辨率 × 明暗 | 4–6h | 真实浏览器（Edge CDP）+ 截图流水线 | `phase3-ui-check-report.md` + 截图矩阵 |
| **阶段四** 真实用户体验评估 | 3 核心场景 + SUS | 3–5h | 线上/本地可运行环境 + 测试账号 | `phase4-ux-report.md` |
| **阶段五** 设计与体验优化 | 样式规范 + 组件代码 + 复测 | 4–6h | 设计规范文档 + 前端代码 | `phase5-design-spec.md` / `phase5-design-compare.md` |
| **阶段六** 调研与立项 | 竞品分析 + 方案文档 | 可选 3–4h | 联网检索 | `phase6-research.md`（确认后执行） |

> 合计主体（阶段 0–五）：**约 26–41 小时**等效连续工作时长。每阶段结束输出小结并**停下等确认**（总规则 1）。

---

## 4. 分阶段详细任务

### 阶段 0 — 基线复核

| 任务 | 命令/方法 | 产出 |
|---|---|---|
| 0-1 后端测试与覆盖率 | `mvn -o -B -ntp test`（本机固化：`D:\xm\run_tests.ps1`） | 通过/失败数、耗时 |
| 0-2 前端测试与覆盖率 | `node ./node_modules/vitest/vitest.mjs run --coverage` | 通过/失败数、四维覆盖率 |
| 0-3 类型检查与构建 | `vue-tsc --noEmit`、`vite build` | 错误数、构建耗时 |
| 0-4 依赖审计 | `npm audit --audit-level=high`；后端核对高危 CVE | 漏洞清单 |
| 0-5 线上探测 | `/actuator/health`、`/api/info`、前端首屏 | 可用性 + 冷启动耗时 |
| 0-6 版本/配置核对 | 5 源版本、`render.yaml` 与 `application-prod.yml` 差异 | 配置漂移清单 |

**验收**：基线表全部为实测值或明确标注「待基线」。

---

### 阶段一 — 代码审查

**范围**：后端 100 个主代码文件（重点 service 27 / controller 13 / config 17 / security 3）+ 前端 19 视图 + 24 工具模块 + 7 组件 + 部署脚本。

**方法**：静态分析工具辅助 + 逐模块人工审查（总规则要求的组合方式）
- 后端：`mvn -o -B -ntp compile`（严格告警）、依赖树核对、`@Transactional`/并发/异常吞没/日志打码/越权/注入 逐点排查
- 前端：`vue-tsc`、ESLint、事件监听与定时器泄漏、XSS（`v-html`）、状态竞态
- 安全专项：鉴权绕过、IDOR/越权、SSRF、上传（magic bytes）、密钥泄露、CORS/安全响应头、速率限制绕过

**重点批次划分**（避免上下文超限，分批出具小结）

| 批次 | 覆盖范围 | 关注点 |
|---|---|---|
| B1 | `ai/` + `config/` | AI 降级链、embedding dimensions 透传、并发闸门、向量库持久化、Redis 配置 |
| B2 | `service/` 前半 | 简历分析/解析、面试出题评估、会话与乐观锁 |
| B3 | `service/` 后半 + `ai/AutoKnowledge*` | RAG 检索与自动补充（阈值、配额、确定性 ID、循环依赖）、学习中心口径 |
| B4 | `controller/` + `security/` + `interceptor/` | 鉴权、限流、参数校验、错误语义、越权 |
| B5 | `repository/` + `entity/` + `util/` | N+1、索引有效性、SQL 注入面、工具类边界 |
| B6 | 前端 views/components/utils | 逻辑错误、内存泄漏、主题一致性、可访问性、死代码 |
| B7 | 部署与配置链 | Dockerfile、render.yaml、vercel.json、CI、keepalive、schema 漂移 |

**输出**：`phase1-code-review.md` — 每条含 **文件路径:行号 / 问题描述 / 级别（P0 阻断·P1 严重·P2 一般·P3 建议）/ 具体修复建议**；并附「与上轮 75 项发现的对照表」（已修/仍存/新增/失效）。

---

### 阶段二 — 问题修复

| 规则 | 内容 |
|---|---|
| 优先级 | P0、P1 **必须 100% 修复**；P2 **≥90%**；P3 入 backlog |
| 提交纪律 | 每个问题/每组同源问题 **独立 commit**，格式 `类型(范围): 描述`，body 必含**问题描述 / 解决思路 / 验证方法** |
| 验证 | 每次修复后**全量回归**（后端 + 前端 + 类型检查 + 构建） |
| 测试真实性 | **严禁修改断言或删除失败用例凑指标**；测试变更须对应有意行为变更并在 body 说明 |
| 覆盖要求 | 新增/修改代码须有单测；核心业务模块覆盖率目标 **80%**（后端 pom 与前端 vitest 门槛均已固化 80%，不达标即构建失败） |
| 不可达目标 | 若首轮无法达标，**如实记录差距**并给出改进计划，不做无依据承诺 |

**输出**：`phase2-fix-record.md`（问题 → 方案 → 状态 → 验证方法，逐条对应 commit）+ `backlog-p3.md` 更新。

---

### 阶段三 — 页面/界面检查

**环境**：真实浏览器自动化（本机 Edge + CDP，复用 `D:\xm\cdp.py` / `launch-edge.ps1`）。

**分辨率矩阵**（完全对齐模板要求）：

| 类别 | 分辨率 |
|---|---|
| 桌面端 | 1920×1080、1366×768 |
| 平板端 | 768×1024 |
| 移动端 | 375×667 |

**检查项**：布局塌陷/错位/横向滚动条；导航溢出与可用性；**明暗模式一致性**；加载/空数据/错误三态；字体与截断；交互反馈；浏览器兼容性（本机**只能实跑 Edge/Chrome 内核**，Firefox/Safari 若不可用则如实标注环境限制，不伪造结果）。

**页面清单（19 视图）**：Login、Home、Resume、ResumeHistory、Interview、Agent、Jobs、JobAnalysis、Favorites、History、Knowledge、Learning、Progress、WrongBook、Calendar、Profile、Admin、NotFound（+ 变更日志弹窗）。

**预估页次**：19 视图 × 5 分辨率（含明暗抽样）≈ **95+ 页次 / 截图**，与上轮（92 页次、95 图）可比。

**输出**：`phase3-ui-check-report.md` + `screenshots/` + `capture-manifest.json`（页面 × 分辨率矩阵，含问题截图与修复清单）。

---

### 阶段四 — 真实用户体验评估

**核心场景（3 个，覆盖主路径）**：
1. 注册/登录 → 简历分析（粘贴/上传）→ 查看评分与建议 → 历史回看
2. 创建面试会话 → 生成题目 → 作答提交 → 评分反馈 → 错题本/学习中心
3. RAG 知识库问答（含自动补充触发）→ 智能体对话 → 结果分享

**记录内容**：完整操作路径与步骤、各步骤耗时、问题点（功能异常/操作繁琐/反馈不及时/文案不清，附**时间戳 + 重现步骤**）、**每场景 ≥2 张关键截图**。

**方法**：SUS 自评给出基线分（目标 ≥80）+ 各场景**任务完成率**；与上轮 **SUS 62.5** 对比。

**输出**：`phase4-ux-report.md` — 用户旅程文字版 / 问题清单（按体验·流程·性能·文案分组并标优先级）/**改进建议 ≥5 条**（问题·方案·预期效果·实现难度·优先级）/**新功能建议 ≥3 条**（用户价值·难度·优先级 + 初步 roadmap）。

---

### 阶段五 — 设计与体验优化

**交付形式**：样式规范文档 + **组件代码**（不要求 Figma 源文件）。
1. **色彩系统**：主色/辅助色/中性色色值标准与使用规范
2. **排版规范**：字体层级、行高、字重
3. **组件规范**：按钮/表单/卡片等核心组件统一（对齐现有 `Base*` 组件）
4. **响应式规则**：各断点布局适配规则
5. **设计参考**：联网检索主流设计平台优质案例，**注明来源链接**，并确保在 Vue3 + Element Plus 现有框架下可落地

**复测**：用阶段四**相同场景**复测，给出优化前后对比数据与效果说明，确保功能完整性不受影响（回归全绿）。

**输出**：`phase5-design-spec.md` + `phase5-design-compare.md` + 组件代码 commit。

---

### 阶段六 — 调研与立项（可选，需单独确认）

调研 3–5 个同类高质量开源项目（优先 star > 5k、近 3 个月有更新），输出对比表（功能模块/架构/技术栈/社区活跃度/许可证/部署复杂度）+ SWOT + 1–5 分评分，并形成含目录、引言、调研分析、方案详情、风险评估的正式文档。**star 数等关键指标必须联网检索核实并提供来源链接。**

---

## 5. 资源与前置条件

| 类别 | 需求 | 状态 |
|---|---|---|
| JDK 21 | `D:\xm`（`D:\xm\bin\java.exe`） | ✅ 已验证 |
| Maven | `D:\xm\apache-maven-3.9.15`，本地仓库 `D:\xm\maven-repo` | ✅ 已验证 |
| Node | `22.22.2-3`（managed） | ✅ 已验证 |
| 浏览器 | 本机 Edge + CDP 脚本（`D:\xm\launch-edge.ps1`） | ✅ 已具备 |
| 线上环境 | Render + Vercel（已探测可达） | ✅ 200 |
| 测试账号 | 演示账号 / `SEED_ADMIN_USERNAME` | ⚠️ 需确认可用凭证 |
| AI 额度 | Agnes + 智谱（降级链）、百炼 embedding | ⚠️ 需确认可消耗 |
| 磁盘 | 截图与报告约数百 MB，全部落在 D 盘 | ✅ |

---

## 6. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 上下文超限导致审查质量下降 | 漏审、结论变浅 | 严格分批（B1–B7），每批出小结并可断点续跑 |
| Render 免费层冷启动（实测可达 98s） | 阶段三/四超时误判 | 前端与评测脚本超时设 >100s + 重试循环 |
| AI 免费额度耗尽/限流 | 阶段四场景失败率虚高 | 如实标注环境等级；必要时降级为「非 AI 界面可用性」结论 |
| 修复引入回归 | 基线倒退 | 每次修复后全量回归；失败即回退，不修改断言 |
| 线上配置与仓库不一致（schema 漂移） | 生产问题排查失真 | 阶段 0 采集配置漂移清单，阶段一 B7 批次专项 |
| 浏览器兼容性无法覆盖 Firefox/Safari | 结论不完整 | 如实标注环境限制，不编造结果 |

---

## 7. 交付物清单（对齐模板「最终交付报告」）

| 交付物 | 位置 |
|---|---|
| 本执行计划书 | `execution-plan.md` |
| 阶段一 标准化问题清单 | `phase1-code-review.md` |
| 阶段二 修复记录 + P3 backlog | `phase2-fix-record.md`、`backlog-p3.md` |
| 阶段三 页面矩阵报告 + 截图 | `phase3-ui-check-report.md`、`screenshots/`、`capture-manifest.json` |
| 阶段四 体验报告 | `phase4-ux-report.md`、`screenshots-phase4/` |
| 阶段五 样式规范 + 前后对比 | `phase5-design-spec.md`、`phase5-design-compare.md` |
| **最终交付报告**（功能清单 / 测试结果 / 修复记录 / SUS 与 roadmap / 遗留风险） | `final-delivery-report.md` |

---

## 8. 放行状态

✅ **已于 2026-09-20 12:51 放行**（C-1 ~ C-5 全部确认，见第 0 节）。

执行方式：自阶段 0 → 阶段一顺序推进；**每阶段结束输出小结并等待确认再进入下一阶段**（总规则 1）。

**新增验收标准（因 C-5 变更）**：每个修复的完整验收链为
**本地回归通过 → commit（含问题/思路/验证 body）→ push → 线上部署生效 → 线上复验通过**。

---

**计划版本**：v1.0 · **编制日期**：2026-09-20

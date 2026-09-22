# 三个抖音求职视频 → 本项目落地映射（v1.35.0 / v1.36.0）

> 整理日期：2026-09-22 · 适用项目：AI 智能面试辅助平台（interview-guide）
> 说明：v1.35.0 基于三个视频的公开文案落地；v1.36.0 在完整浏览视频 1 的 10 页图文、
> 视频 2 的完整画面与视频 3 的章节要点后，补充落地图文中未展开的核心产物。

---

## 一、三个视频的内容提取

### 视频 1 ——《VibeCoding大赏｜求职Skill》（作者：GK同学）

**原文案关键内容**（已逐句核对公开文案）：

- 作者背景：「作为乱七八糟项目负责人，我这几年也面试过不少人」
- 核心洞察：「在日常对话的口吻能把自己的故事讲得逻辑自洽，我面到过的不过七八个。」
- **关键结论**：「逻辑自洽需要建立在对自己的完整认知上，所以我觉得**求职的开始，简历还不是第一步。而是——挖掘自己的长处和特质**。」
- 产品形态：「所以我做了一个 **Agent Skill**。从挖掘自己的长处开始，按照你真实的想法帮你找岗位，陪你练面试直到拿到 offer。」
- **方法论（本项目的落地依据）**：「想了很久怎么才是挖掘真实经历的结构，最后参照麦肯锡的一些方法，定为：**证据 → 行为 → 能力 → 可投岗位信号**」
- 第二个关键环节：「怎么**有节奏地**找岗位并投递。因为它是 Agent，所以靠 Agent 的强大信息搜索能力，帮助你海淘符合你发展需求的岗位，写明白：**为什么适合、差距在哪、30 天里补什么 case、适合什么赛道的公司**」
- 收尾金句：「**你不会没有能力，但只是很多人说不清自己的能力。**」

**提取到的可复用 Skill 要点**：
1. 求职起点是「自我挖掘」而非「改简历」；
2. 四层抽象结构：证据（事实）→ 行为（动作）→ 能力（可迁移）→ 岗位信号（岗位族映射）；
3. 岗位侧输出四件套：why-fit / gap / 30 天 case / 赛道；
4. 以 Agent 形态承载，靠检索能力找岗位。

### 视频 2 ——《求职agent大更新，感谢社区贡献者们～》（作者：跑跑蹦蹦跳跳）

**原文案关键内容**：

- 「这个是做开源社区，最有成就感的时刻，看到项目……」（**视频未展开**具体功能清单）
- 关联的公开开源项目为 **BossHunter**（GitHub，v2.4.0），其公开描述为：
  > 「某直聘智能求职 Agent：**本地完成岗位采集、AI 评分、人工确认投递、回复监测与定制简历生成**。」
- 该开源项目同时标注了风险提示：「自动化操作招聘平台存在账号限制或封禁风险。本项目仅供学习、研究和个人求职效率提升；请遵守平台规则。」

**提取到的可复用 Skill 要点**（BossHunter 五段式闭环）：
1. 岗位采集（本地）；
2. AI 评分（匹配度打分）；
3. **人工确认投递**（human-in-the-loop，不自动投）；
4. **回复监测**（谁回了、该跟进谁）；
5. **定制简历生成**（针对每个岗位定制）。

### 视频 3 ——《主流AI面试工具从夯到拉真实测评，不吹不黑！》（作者：李捏捏）

**原文案关键内容**：从「夯」到「拉」的档位排名，逐一点评主流 AI 面试工具。
本项目的 `docs/ai-interview-tool-integration.md` 已对该视频做过完整分析（9 款工具逐一分析 +
可落地优势 → 集成方案），**本次不重复**，仅在第 4 节做落地状态盘点与缺口识别。

---

## 二、落地映射总览（三个视频 → 本项目）

| 视频 | 提取的能力 | 本项目落地形态 | 状态 |
|---|---|---|---|
| 视频1 求职Skill | 证据→行为→能力→岗位信号 四层挖掘 | `CareerProfileService.mine()` + `POST /api/career/mine` + 前端「求职诊断」页 | ✅ 本次新增 |
| 视频1 求职Skill | 有节奏地找岗位（why-fit/gap/30天/赛道） | `CareerProfileService.plan()` + `POST /api/career/plan` + 前端同页第二步 | ✅ 本次新增 |
| 视频1 求职Skill | 以 Agent 形态承载 | 智能体新增工具 `mineCareerAssets`（工具数 9 → 11） | ✅ 本次新增 |
| 视频2 BossHunter | 岗位采集 | 已有：`JobAgentService` 多源采集 + `WebJobSearcherService` 联网搜岗 | ⚪ 已有（v1.27~v1.31） |
| 视频2 BossHunter | AI 评分 | 已有：`JobMatchService` 简历×岗位匹配打分 + `POST /api/jobs/match` | ⚪ 已有（v1.29.0） |
| 视频2 BossHunter | **人工确认投递** | `JobApplicationService.confirmApply()` + `POST /api/application/{id}/confirm` | ✅ 本次新增 |
| 视频2 BossHunter | **回复监测** | 状态机 + `board()` 待跟进判定 + 前端「投递看板」 | ✅ 本次新增 |
| 视频2 BossHunter | **定制简历生成** | `TailoredResumeService.tailor()` + `POST /api/application/{id}/tailor` | ✅ 本次新增 |
| 视频2 BossHunter | 投递台账可被智能体读取 | 智能体新增工具 `getMyApplications` | ✅ 本次新增 |
| 视频3 测评 | 9 款工具优势 | 见 `docs/ai-interview-tool-integration.md`（★ 项已落地） | ⚪ 已落地 |

---

## 三、本次新增的工程实现（v1.35.0）

### 3.1 求职 Skill（视频 1）

**后端**

| 文件 | 职责 |
|---|---|
| `service/career/CareerProfileService.java` | 四层挖掘 `mine()` + 节奏计划 `plan()`；输入经 `PromptSanitizer` 消毒 + 长度截断，调用纳入 `AiConcurrencyGuard`，输出走 `JsonRepairUtil.repairOrFallback` 兜底 |
| `controller/CareerController.java` | `POST /api/career/mine`、`POST /api/career/plan`；userId 从 JWT 提取；可选参数 null 归一为 `""`（避免 "null" 字面量混入 prompt） |

**接口契约**

```bash
POST /api/career/mine
{"narrative":"我做过二手交易平台，把慢查询改成 ES，日活 30→200","targetTrack":"Java 后端"}
→ {"positioning":"...","assets":[{"evidence":"...","behavior":"...","capability":"...","jobSignals":["..."]}],
   "strengthTags":[],"blindSpots":[],"nextSteps":[]}

POST /api/career/plan
{"targetJob":"Java 后端","resumeText":"...","mineSummary":"可选"}
→ {"whyFit":"...","gaps":[{"item":"...","level":"HIGH","action":"..."}],
   "thirtyDayPlan":[{"week":"第1周","focus":"...","tasks":[],"deliverable":"..."}],
   "tracks":[{"track":"...","companies":[],"reason":"..."}],"cadence":[]}
```

**前端**：新增 `views/CareerView.vue`（两步向导：经历挖掘 → 节奏计划；挖掘结果可一键回填到计划输入），
路由 `/career`，顶部导航「求职诊断」。

### 3.2 投递闭环（视频 2）

**后端**

| 文件 | 职责 |
|---|---|
| `entity/JobApplicationEntity.java` | 投递台账（岗位快照 + 8 态状态机 + 定制简历 + 跟进时间），`(user_id, job_id)` 唯一 |
| `repository/JobApplicationRepository.java` | 按用户/状态查询与计数 |
| `service/job/JobApplicationService.java` | 幂等加入台账、人工确认投递、状态推进（回复监测）、看板与转化漏斗、待跟进判定 |
| `controller/JobApplicationController.java` | `/api/application/**` 七个端点；按 ID 操作全部做归属校验防 IDOR |
| `service/career/TailoredResumeService.java` | 针对岗位生成定制简历（重写条目 + 命中/待补关键词 + 定位陈述 + 建议） |

**状态机**

```
PLANNED（待投递） --confirm--> APPLIED（已投递） --> VIEWED（已查看） --> REPLIED（有回复）
                                                      --> INTERVIEW（面试中） --> OFFER（已拿 Offer）
                                                      --> REJECTED（已淘汰） / WITHDRAWN（已放弃）
```

**回复监测**（read-time 计算，无需定时任务）：
1. 显式设置了 `nextActionAt` 且已到期；
2. 处于「已投递/已查看」且距投出超过 **7 天**仍无回复。

**前端**：新增 `views/ApplicationView.vue`（统计卡 + 转化漏斗 + 待跟进区 + 状态推进 + 定制简历弹窗），
路由 `/applications`，顶部导航「投递看板」；「招聘广场」岗位详情新增「加入投递计划」按钮。

### 3.3 智能体工具层（两个视频的能力都接进对话）

`AgentTools` 工具数 **9 → 11**：

| 新工具 | 用途 |
|---|---|
| `mineCareerAssets(narrative, targetTrack)` | 对话中直接做四层职业资产挖掘 |
| `getMyApplications()` | 对话中读投递台账：状态计数、转化漏斗、待跟进清单 |

`AgentService` 通过 **setter 注入** 新服务（既有 10 参构造器与全部调用点保持不变，零破坏性改动）。

---

## 四、视频 3 的落地盘点（不重复实现，仅对账）

`docs/ai-interview-tool-integration.md` 中标注 ★ 的均已落地。经本次核对，**仍存在的缺口**如下
（未在本轮实现，列为后续可选）：

| 缺口 | 来源工具 | 说明 | 建议 |
|---|---|---|---|
| 实时面试辅助（正式面试中实时识别问题给思路） | 可面猫 / Offerin / 白瓜面试 | 本项目已具备 ASR（`utils/speech.ts`）+ 术语纠正 + SSE 流式提示，但**未做「实时面试模式」**（连续识别→自动出题→即时提示） | 与现有合规边界需再确认：本项目已明确「隐蔽实时偷听类作弊不实现」，若要做需限定为「自练场景的实时陪练」 |
| 岗位押题 | 多面鹅 / 面星 | 按岗位/行业给出高概率面试题 | 可基于现有 `InterviewService.generateQuestions` + JD 反向生成，成本低 |
| 面试复盘评分报告 | Final Round AI / Huru | 已有三维评分与报告分享卡片；缺「跨场次趋势分析报告」 | 已有 `ProgressView` + `scoreTrend`，可合并为单份复盘报告 |
| 多设备联动 | Offerin / 白瓜面试 | 手机 ↔ 电脑同步 | 免费层架构（Render 512MB）成本较高，暂不建议 |

> 结论：视频 3 的**可合规落地项已基本完成**，剩余项多为「需重新界定合规边界」或「基础设施成本较高」，
> 故本轮把工程量集中在视频 1、视频 2 的**方法论落地**上。

---

## 五、合规边界（明确不做）

1. **不自动登录、不自动投递招聘平台**。BossHunter 开源项目自身也提示「自动化操作招聘平台存在账号限制或封禁风险」。
   本项目只做「本地台账 + 定制简历 + 状态提醒」，实际投递由用户点击 `applyUrl` 自行完成。
2. **不伪造简历**。`TailoredResumeService` 的提示词明确要求：不得新增候选人未提及的经历、技能、公司、数字或奖项；
   JD 要求但候选人未具备的能力只能进入 `missingKeywords` 作为「待补提示」，**不允许写进改写条目**。
3. **不做隐蔽实时偷听/代答**。延续 `ai-interview-tool-integration.md` 第 4 节的既有边界。
4. **职业资产挖掘不编造**。`CareerProfileService` 提示词要求「严格基于自述内容，禁止编造未提及的经历、数字、公司或奖项；
   自述含糊处按最保守理解」。

---

## 六、验证记录（真实执行）

| 项 | 结果 |
|---|---|
| 后端编译 | `mvn -DskipTests compile` BUILD SUCCESS |
| 后端全量单测 | **803 tests, 0 failures, 0 errors**（BUILD SUCCESS） |
| 新增测试 | `CareerProfileServiceTest` 8 · `JobApplicationServiceTest` 13 · `CareerControllerTest` 5 · `JobApplicationControllerTest` 13 · `AgentToolsTest` 新增 6 |
| 前端类型检查 | `vue-tsc --noEmit` 0 错误 |
| 前端单测 | **25 files / 286 tests 全过** |
| 数据库变更 | `job_application` 表已同步 `SchemaInitializer`（启动幂等建表）与 `schema.sql`（两处一致） |

---

## 七、后续可选增强（未做，供决策）

> v1.36.0 已实施其中第 1、3 项（面试故事库 + 求职诊断持久化），见第八节。

1. **岗位押题**：基于 JD 反向生成高概率题（复用现有出题链路，成本低、合规无风险）。
2. **投递提醒进面试日历**：把 `nextActionAt` 与已有 `interview_event` 打通，形成统一提醒。
3. **求职诊断结果持久化**：当前挖掘结果为即时应答不落库；若要支持「回看历史定位」需加表。
4. **投递转化复盘报告**：把 `board().funnel` 做成周报，与 `ProgressView` 的练习趋势并列展示。

---

## 八、v1.36.0 增量（基于视频完整画面补充落地）

v1.35.0 落地时仅依据视频文案。本次完整浏览了三个视频的画面细节后，补齐图文中明确出现、
但文案未展开的核心产物：

| 来源 | 视频中看到的内容 | 落地形态 | 状态 |
|---|---|---|---|
| 视频1 图9「面试」 | interview-story-bank.md / interview-practice.md；六项检查清单（结构清楚/证据具体/贴合岗位/无废话/无风险表达/能被追问住）；「先回答，再追问，再复盘」 | `StoryBankService`（extract/check/followUp）+ `story_bank` 表 + `/api/story-bank/**` + CareerView 第三步 | ✅ 本次新增 |
| 视频1 图6「岗位假设」 | 匹配分 78 分卡：匹配证据/主要差距/30天补强；「低于 60 分不建议投递，只进入观察池」 | `CareerProfileService.plan()` 输出新增 `applyAdvice`（score/matchedEvidence/verdict/reason）+ CareerView 假设卡 | ✅ 本次新增 |
| 视频1 图10「记忆库」 | 最终产物是个人求职记忆库（故事库/证据链/投递追踪等） | 故事库随 `story_bank` 表持久化；证据链（mine 结果可回填）与投递追踪（`job_application`）v1.35.0 已有 | ✅ 组合闭环 |
| 视频1 图3/8 痛点 | 简历写「参与/协助/负责」没有动作结果；`needs_proof` 标记缺少证据的表达 | `extract` 提示词禁止空动词；`check` 六项质检中的「证据是否具体/有没有风险表达」 | ✅ 融入提示词 |
| 视频3 测评 | 面试猫优点「回答自然度高」；面试狗/俄莱面「无废话、贴合」被一致视为高分项 | `InterviewService.evaluateAnswer` 评分注意事项新增：空话废话与风险表达如实扣分并指出原句 | ✅ 本次新增 |
| 视频2 BossHunter | 智能体能力集持续扩展（社区共创模式） | AgentTools 工具数 11→12（新增 `prepareInterviewStories`） | ✅ 本次新增 |

### 8.1 新增接口契约

```bash
GET  /api/story-bank                          # 故事列表
POST /api/story-bank/extract                  # {"narrative":"...","targetTrack":"可选"}
                                              # → {"stories":[{title,situation,task,action,result,evidence,capabilities[]}]}
POST /api/story-bank                          # 保存故事（title 必填）
POST /api/story-bank/{id}/check               # {"answer":"口述回答"} → 六项质检 JSON
POST /api/story-bank/{id}/followup            # {"answer":"口述回答"} → {"followups":[3 条追问]}
DELETE /api/story-bank/{id}
```

六项质检固定项：结构是否清楚 / 证据是否具体 / 是否贴合岗位 / 有没有废话 / 有没有风险表达 / 能否被追问住。

### 8.2 验证记录（真实执行）

| 项 | 结果 |
|---|---|
| 后端编译 | `mvn -DskipTests compile` BUILD SUCCESS |
| 后端全量单测 | **826 tests, 0 failures, 0 errors** |
| 新增测试 | `StoryBankServiceTest` 10 · `StoryBankControllerTest` 7 · `AgentToolsTest` 新增 5（工具注册数 11→12） |
| 前端类型检查 | `vue-tsc --noEmit` 0 错误 |
| 前端单测 | **25 files / 286 tests 全过** |
| 数据库变更 | `story_bank` 表已同步 `SchemaInitializer` 与 `schema.sql`（两处一致） |

### 8.3 合规边界（延续 v1.35.0）

故事提炼与质检提示词延续「不编造」约束：严格基于用户自述，自述不足时如实标注「待补充」，
不虚构经历、数字与奖项；六项质检中的「风险表达」项同时提示夸大编造风险。

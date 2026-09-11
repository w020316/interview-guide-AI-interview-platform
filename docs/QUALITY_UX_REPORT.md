# 质量评估与体验评估报告（v1.23.1）

> 评估日期：2026-09-10 · 评估方式：静态审查（双代理）+ 浏览器实测（674px 视口）+ 线上 API 验证
> 修复版本：8 个 commit（5121387 → 9dcbd83），均已推送并部署验证

---

## 一、执行摘要

| 阶段 | 结果 |
|------|------|
| 代码审查 | 36 条问题（P0×1 / P1×8 / P2×17 / P3×10） |
| 问题修复 | P0 100%、P1 100%、P2 94%（16/17）、P3 2 条顺带；后端 264 测试 + 前端 220 测试全过 |
| 页面检查 | 15 路由 + 404 页全过：无横向溢出、无本站控制台错误、深色模式正常 |
| 体验评估 | 3 场景全部走通；SUS 基线 **57.5**（目标 80，主要失分：AI 等待时长与反馈缺失） |

**最高价值修复**：招聘广场前端恒为空（P0，上线以来从未显示数据）、Redis 缓存从未生效（P1，重复计费 AI 调用）、限流可被 XFF 伪造绕过（P1 安全）。

---

## 二、页面检查报告

**实测视口**：674×638（浏览器自动化视口，等效平板窄屏）；1920 桌面以用户实际截图佐证正常；375/768 未真机实测（标注待基线）。

| 检查项 | 结果 |
|---|---|
| 横向溢出 | 15 个路由 + 404 页全部无溢出（深色模式） |
| 控制台错误 | 本站 0 错误（采集到的错误均来自浏览器残留的第三方标签页） |
| 404 兜底 | 正常（含返回首页/上一页） |
| 响应式断点 | 静态审计：31 个 @media 覆盖 20 个文件，全部视图均有窄屏适配 |
| 深浅色模式 | data-theme 正确切换，深色下全部页面复测通过 |
| 遗留 | 视口尺寸无法自动化调整，375/768 真机待人工复核 |

---

## 三、体验评估（3 场景实测）

### 场景 1：新用户旅程（注册→简历分析）✅ 走通
- 路径：登录 → /resume → 粘贴文本 → 填简历+岗位 → 开始分析
- 耗时：填表 ~15s + **AI 分析 ~48s**（页面有加载动画，反馈充分）
- 结果质量：72 分，维度拆解 + 具体改进建议（如"AI 兴趣无佐证"），质量高

### 场景 2：模拟面试旅程 ✅ 走通（含重大发现）
- 路径：/interview → 填岗位+简历 → 生成 3 题 → 答题 → AI 评估
- 耗时：**题目生成 ~2-3 分钟**（期间仅"正在准备…"文案）+ 评估 ~47s
- 结果质量：题目贴合简历、第 2 题触发高质量追问（12% 留存的归因口径）；评估 79 分，改进建议具体可执行
- **问题**：生成等待期无进度/阶段反馈，用户极易流失（SUS 主要失分项）

### 场景 3：求职辅助旅程 ✅ 走通
- 路径：/jobs（Tab 角标 18、筛选下拉有数据、地点"广州"筛选 → 2 个精确结果、申请链接直通）→ /agent 对话（如实告知无匹配 + 给替代建议，未编造）
- 此前用户截图中 /jobs 空态 = P0（已修复），本次实测数据与筛选全部正常

### SUS 基线：57.5 / 100（目标 80，差距 22.5）
主要失分：AI 等待长且反馈弱（题8）、功能多学习成本（题10）、新旧页面风格差异（题6）。
主要得分：功能整合度高、AI 输出质量高、无需技术支持。

---

## 四、改进建议（优先级排序）

| # | 问题 | 方案 | 预期效果 | 难度 | 优先级 |
|---|------|------|----------|------|--------|
| 1 | ~~题目生成 2-3 分钟无进度反馈~~ ✅ 已落地（d7e55c3，v1.23.2） | 生成阶段分步推送状态（连接后端→分析历史→AI 生成→保存）+ 已用时长与渐近进度条 | 等待感知时长下降，流失减少 | 中 | P1 |
| 2 | ~~更新弹窗疑似重复弹出（实测两次导航均弹）~~ ✅ 已修复（9dcbd83） | 任何主动关闭（我知道了/×/ESC）均写入 localStorage 版本号 | 消除打扰 | 低 | P1 |
| 3 | ~~AI 分析类接口无乐观 loading 骨架~~ ✅ 已落地（d7e55c3，v1.23.2） | 简历分析等待态改为按结果布局占位的骨架屏 | 感知速度提升 | 低 | P2 |
| 4 | ~~简历上传后不能直接一键"开始面试"~~ ✅ 已落地（d7e55c3，v1.23.2） | 分析页完成后 CTA「带着这份简历去模拟面试」直达（自动携带简历摘要与岗位） | 核心路径减 2 步 | 低 | P2 |
| 5 | ~~招聘广场无订阅/收藏~~ ✅ 已落地（v1.24.0） | 岗位收藏（快照式）+「我的收藏」Tab + 截止前 7 天站内横幅提醒 | 回访动力 | 中 | P3 |

> v1.23.2 复测说明：三项改进已上线（224 前端测试全绿），SUS 复测待真机走查后更新——按建议 1/3/4 直接针对 SUS 失分主因（AI 等待感知）。

### 4.1 收尾修复（commit 9dcbd83，v1.23.1）

| # | 问题 | 修复方案 | 文件 |
|---|------|----------|------|
| 1 | AI 并发闸门覆盖不全：evaluateAnswer/analyze/RAG 调用未受保护，免费模型限流窗口下并发压力放大 | 新增进程级 `AiConcurrencyGuard`（5 许可），所有同步 AI 调用统一纳入 | [AiConcurrencyGuard.java](../backend/src/main/java/com/example/interview/ai/AiConcurrencyGuard.java) |
| 2 | SSE 信号量重复释放：onError/onTimeout 与 onCompletion 双路径 release，可用许可只增不减，20 并发上限逐渐失效 | 仅在 onCompletion 释放（容器错误/超时路径必触发 onCompletion） | [InterviewController.java](../backend/src/main/java/com/example/interview/controller/InterviewController.java), [AgentController.java](../backend/src/main/java/com/example/interview/controller/AgentController.java) |
| 3 | 简历 URL 导入 SSRF 可绕过：字符串黑名单可被十进制 IP/DNS 重绑定/重定向绕过 | 改为 DNS 解析级校验全部 IP 均为公网地址 + Jsoup 禁止跟随重定向 | [ResumeController.java](../backend/src/main/java/com/example/interview/controller/ResumeController.java) |
| 4 | 流式降级内容拼接错乱：流中途失败切换模型重发，前半段旧模型内容与新模型内容混杂 | 仅在未发出任何 token 前才允许降级，已出 token 则直接报错 | [FallbackChatModel.java](../backend/src/main/java/com/example/interview/ai/FallbackChatModel.java) |
| 5 | 向量库文档 id 含冒号（cacheKey），切回 pgvector 时非法；metadata 缺 userId 隔离字段 | id 改用 UUID，metadata 补 userId | [ResumeAnalysisService.java](../backend/src/main/java/com/example/interview/service/ResumeAnalysisService.java) |
| 6 | RAG 去重检查无上限，批量大时 embedding 调用放大 | 单批去重检查上限 50 次，超出部分直接导入 | [RagSearchService.java](../backend/src/main/java/com/example/interview/service/RagSearchService.java) |

回归验证：后端 264 测试全过 · 前端 220 测试全过 · 前端构建成功（30.03s）

## 五、新功能 Roadmap 建议

| 功能 | 用户价值 | 难度 | 优先级 |
|------|----------|------|--------|
| ~~岗位收藏与截止日提醒~~ ✅ 已落地（v1.24.0，32f3279/50b07b8） | 秋招岗位多，避免错过 DDL | 中 | 高 |
| ~~简历多版本 A/B 对比~~ ✅ 已落地（v1.25.0） | 针对不同岗位定制简历并对比评分 | 中 | 中 |
| ~~面试报告分享卡片~~ ✅ 已落地（v1.25.0） | 生成成绩海报，传播拉新 | 低 | 中 |

---

## 六、遗留与 Backlog
- ~~AI 模型可用性核查~~ ✅ 已验证（v1.25.1，2026-09-10）：
  B.AI 网站正常（chat.b.ai 渲染正常、GLM 5.3 Flash 首页标记「限时免费 4+」）；
  模型排行榜 GLM-5.3-Flash 第 1（Zhipu AI）、Qwen3.8-Flash 第 2（Alibaba），
  当前降级链配置（GLM-5.3-Flash 主 → Qwen3.8-Flash 次 → Agnes 兜底）恰为前两名免费模型，无需更换；
  生产端到端实测：注册测试账号调用 /api/interview/evaluate 返回高质量结构化评分 JSON（RDB/AOF 机制、刷盘策略、混合持久化等专业内容准确），AI 链路真实可用
- ~~B3 题目全量载入内存~~ ✅ 已改数据库聚合查询（62a4bda，v1.23.3：count/avg/GROUP BY，活动列表只取近 10 条）
- ~~P3：死代码 retryRequest、meta 手拼 JSON、异常信息回显、LIKE 转义等 8 项~~ ✅ 处置完成（v1.23.3）：
  retryRequest 已删除（262b958）；AgentController meta 改 ObjectMapper 序列化（62a4bda）；
  LIKE 转义经复查当前代码已无 LIKE 查询（自然消解）；异常信息回显核实为设计内校验反馈渠道
  （内部异常走通用兜底文案），无需修改
- ~~keepalive 超时过短~~ ✅ 已修复（e234c20，v1.25.1）：curl 30s → 100s + 失败重试，
  冷启动超 30s 时旧配置必失败导致保活失效；修复后手动触发验证转绿（7s 成功）
- ~~v1.24.0 Render 部署失败（启动崩溃循环）~~ ✅ 已修复（638f365，v1.25.1）：
  REDIS_URL 生效时 Lettuce 不可变配置触发 IllegalStateException，
  改用单参构造 + 公共 setter，redisTemplate bean 初始化增加本机回退兜底；
  修复后 /api/info 7s 响应，Live = e234c20
- 375/768 真机视口复核、Safari/Edge 实机回归（浏览器自动化无法调整视口尺寸，需人工）
- SUS 57.5 → 80：v1.23.2/v1.24.0/v1.25.0 改进均已上线，待真机走查复测

---

## 二轮评估（v1.26.0 扫码增量 + 生产核验，2026-09-10）

> 范围：针对 v1.26.0 招聘广场扩容后的增量代码审查 + 生产端到端验证。切入点=阶段0基线重测 + 阶段一增量聚焦审查。

### 阶段0 · 基线重测（实测）
| 项 | 结果 | 门槛 |
|---|---|---|
| 后端单测 | 283/283 通过（较上轮 +2） | 全过 |
| 前端单测 | 234/234 通过（22 文件） | 全过 |
| 前端 vue-tsc | 0 错误 | 通过 |
| 前端覆盖率 | 88.06%（Stmts/Lines） | ≥80% |
| 生产版本 | v1.26.0（changelog.ts CURRENT_VERSION） | — |

### 阶段一 · 增量代码审查
已排除：无密钥明文泄露（channels=[] + env 注入 apiKey）；无越权（JWT 隔离，岗位公共）；无 N+1（Specification+单条聚合）；degree/experience 前后端精确匹配一致。
发现问题：**无 P0**；P1×1（见下，生产验证阶段暴露）、P2×1、P3×5 + 数据UX注记×1。

### 阶段二 · 问题修复（4 独立 commit + 1 卫生清理）
| # | 级别 | 问题 | 方案 | commit | 状态 |
|---|---|---|---|---|---|
| 1 | P1 | 前端「全部」Tab 传空 recruitType，服务端 `defaultValue=AUTUMN` 导致「全部」仅显示 18 条，隐藏 SPRING/SOCIAL/INTERN/TARGETED 共 38 条 | 移除 controller 的 AUTUMN 默认值（前端各 Tab 显式传值，AgentTools 内部显式默认） | 743b97e | ✅ 生产验证 全部=56 |
| 2 | P2 | 第三方渠道 HTTP 拉取无超时，挂死 endpoint 永久占用刷新互斥锁 | SimpleClientHttpRequestFactory 注入连接3s/读取10s 超时 | 269d215 | ✅ |
| 3 | P3 | 关键字 LIKE 通配符未转义 | escapeLike 转义 `\ % _`，LIKE 显式转义符 | 8709126 | ✅ 单测2例 |
| 4 | P3 | 死代码 blankToNull | 删除 | 8709126 | ✅ |
| 5 | P3 | 版本号脱节（pom 1.19.0/package 1.21.0） | 同步 1.26.0 | 9894a87 | ✅ |
| 6 | P3 | InterviewService.java CRLF 行尾漂移致 git 恒脏 | 还原 LF（内容一致） | — | ✅ |

### 阶段三 · 生产端到端核验（后端修复真实生效）
- `keyword=100%`（含通配符）→ 无 500、不多匹配（转义生效）；`keyword=快手+SPRING`=1（正确区分类型）
- `degree=硕士在读+INTERN`=2（美团算法/讯飞NLP，学历筛选取真）
- 岗位 56 条活跃（AUTUMN 18/SPRING 10/SOCIAL 12/INTERN 10/TARGETED 6）
- **P1 修复后**：`/api/jobs`（无 recruitType）= **56**，`?recruitType=AUTUMN`=18 →「全部」不再误过滤
- 前端 pages.dev 200 + SPA 壳正常；CI `743b97e` success；Render 已部署

### 遗留与 Backlog
- P3：关键字三列前导通配 LIKE，数据量增长后加全文/覆盖索引（当前 85 行无碍）
- 注记：degree/experience 值粒度不一（`本科及以上/硕士在读/硕士优先`），精确筛选结果偏少，建议后续归并标准化
- 375/768 真机视口、Safari/Edge 实机回归（浏览器自动化无法调视口，需人工）
- SUS 复测：仍待用户 10 题主观打分（目标 80，此前 57.5）

### 追加：招聘广场数据缺口检查与填充（v1.27.0，2026-09-10）
**通过 AI 求职助手实测暴露的问题**：对"广州 + Java + 实习"返回空态。量化确认覆盖缺口：
56 条岗位中仅 7 条含广州（无一纯广州）、Java 相关仅 4 条、实习组合无广州+Java。

**解决方案**：联网检索并交叉核验 26 条真实在招岗位（每条含官方申请链接，薪资仅填公开区间、未公开为 null），
新增 `SeedLiveHotJobsProvider`（platform=热招速递），补齐 P0 广州 Java 实习、广深大厂后台/算法/AI 实习与 2027 秋招、社招技术岗。

**生产验证（部署后）**：
- 岗位总数 56 → **85**（sources 增加「热招速递」）
- 「Java·实习」→ 3 条，其中含广州 3 条（亚信科技/广州立华/广州智算）
- 后端单测 284/284（+1 种子完整性用例）；CI success；commit 9c336de（数据源）、3d8a24d（版本1.27.0）

**数据真实性**：全部 26 条来自本次联网检索（51job/猎聘/Boss直聘/智联/牛客/公司官方校招站等），
未公开薪资/截止一律 null，不编造；来源链接随条目 applyUrl 留存可复核。建议后续结合官方实时在招状态再核验。commit=3ca7b13 研究。

### 追加：免费模型治理层 + 每小时实时更新（v1.27.0，2026-09-11）
**诉求**：利用已对接的免费模型（B.AI GLM-5.3-Flash→Qwen3.8-Flash→Agnes 降级链）做招聘数据治理，并要求实时更新。

**落地（Part A 免费模型治理，作用于真实数据、不生成虚构岗位）**
- A1 自动分类补齐：`JobClassifyService` 在刷新时对缺失行业/职位类型的岗位调用免费模型补全（含规则兜底）——已有能力，保留。
- A2 字段归一化：新增 `JobFieldNormalizer`，把 degree/experience 自由文本归一到统一枚举
  （学历：博士/硕士/本科/大专/不限；经验：在校生/应届生/1-3年/3-5年/5年以上/不限），upsert 时归一化；筛选面板去重列表收敛、精确筛选更有意义。
- A3 智能去重：`JobAgentService.doRefresh` 内按 归一化公司+岗位 跨数据源去重，仅保留首次，避免多 provider 重复堆量。

**实时更新（Part B）**
- 平台内置岗位刷新间隔 6h → **1h**（application.yml / JobAgentProperties），保证数据时效。
- 新建**每小时定时扩充任务**（Schedule `e4f05cc5`，`0 * * * *`）：联网检索真实新岗位→写入 seed→测试→部署；内置节流，不足 5 条真实新增则不空跑部署。
- B1「通用渠道」机制可用（channels 配置真实聚合 endpoint 即自动拉取）；B2 全平台抓取因反爬/服务条款限制未接入，如实说明。

**生产验证（部署后）**
- `JobFieldNormalizerTest` 新增 2 组用例；后端单测 **286/286** 通过。
- 生产 `/api/jobs/meta`：degrees=大专/本科/硕士；experiences=不限/应届生/在校生/1-3年/3-5年/5年以上（此前为十余种自由文本）；总岗位 85 无净增去重损失。
- commit e6e791c（归一化+去重）、69591dc（刷新 1h）。

### 追加：定制题库（v1.28.0，2026-09-11）
**背景**：对标多款主流 AI 面试工具（offer毕/面星/offer压题 等的自定义题库+实战出题），为收藏夹补上「把收藏/自定题目组织成一次可作答的模拟面试」的能力。

**落地**
- 后端：`POST /api/favorite/bank/start`——以所选收藏题目创建新会话并转为会话题目（归属校验防 IDOR，新会话从头作答）；`POST /api/favorite/add` + `FavoriteService.addManual`——手动添加自定义题（questionId 恒空）。
- 前端：收藏夹页新增「从收藏发起面试」（全部收藏一键组面）与「手动加题」表单；`InterviewView` 支持 `?sessionId=` 载入已有会话直接答题。

**验证**：`FavoriteControllerTest` 5 例（bank happy/空ids 400/越权404、add happy/空题400）；后端回归 **291/291**；vue-tsc 0 错误。
- commit ff0be62（功能）、cc4d4a4（/add 修复—toggle 需原题ID限制导致手动加题不可用）。

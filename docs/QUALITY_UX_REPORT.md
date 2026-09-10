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
| 1 | 题目生成 2-3 分钟无进度反馈 | 生成阶段分步推送状态（连接后端→检索知识→生成中 N/3） | 等待感知时长下降，流失减少 | 中 | P1 |
| 2 | ~~更新弹窗疑似重复弹出（实测两次导航均弹）~~ ✅ 已修复（9dcbd83） | 任何主动关闭（我知道了/×/ESC）均写入 localStorage 版本号 | 消除打扰 | 低 | P1 |
| 3 | AI 分析类接口无乐观 loading 骨架 | 骨架屏替代 spinner | 感知速度提升 | 低 | P2 |
| 4 | 简历上传后不能直接一键"开始面试" | 分析页完成后 CTA 直达模拟面试（预填岗位） | 核心路径减 2 步 | 低 | P2 |
| 5 | 招聘广场无订阅/收藏 | 岗位收藏 + 截止日期前站内提醒 | 回访动力 | 中 | P3 |

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
| 岗位收藏与截止日提醒 | 秋招岗位多，避免错过 DDL | 中 | 高 |
| 简历多版本 A/B 对比 | 针对不同岗位定制简历并对比评分 | 中 | 中 |
| 面试报告分享卡片 | 生成成绩海报，传播拉新 | 低 | 中 |

---

## 六、遗留与 Backlog
- B3 题目全量载入内存（数据量增长后需改聚合查询）
- P3：死代码 retryRequest、meta 手拼 JSON、异常信息回显、LIKE 转义等 8 项
- 375/768 真机视口复核、Safari/Edge 实机回归
- SUS 57.5 → 80：按建议 1/3/4 落地后复测

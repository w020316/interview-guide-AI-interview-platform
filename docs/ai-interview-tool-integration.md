# 主流 AI 面试工具优势分析 → 本项目集成方案

> 依据《主流AI面试工具从夯到拉真实测评》(李捏捏, 2026-09)视频所述优劣 + 公开调研整理。
> 目标项目：AI 智能面试辅助平台（Spring Boot 3 + Vue3，interview-guide）。
> 原则：所有数据真实、不编造；**隐蔽性/实时偷听类作弊能力不实现**；模型用于治理真实数据，不虚构岗位。

---

## 一、9 款工具逐一分析

| # | 工具 | 核心优势（视频） | 短板（视频） | 适用场景 |
|---|---|---|---|---|
| 1 | offer毕/先做offer | 支持定制题库、基于真实经历检索、隐蔽性/流畅度好 | — | 以自己简历为底、反复定制练习 |
| 2 | 面试猫 | 界面舒适、语音/图片识别强、回答自然度高 | 0识别一般 | 偏好语音作答与多模态题的用户 |
| 3 | 面试狗 | 主打隐身、算法题表现强 | 回答定制化一般 | 算法题库 + 隐蔽实时辅助 |
| 4 | interview help | 免费开源、功能全面 | 需自行部署、成本高 | 自托管、深度定制团队 |
| 5 | NPC human | 隐身好、底层技术顶配 | 最贵 | 付费重投入用户 |
| 6 | GPT面试AI助手 | 响应快、声纹识别+译文不错 | — | 追求响应速度与多语言的用户 |
| 7 | 面星 | 答案贴合、题库丰富、适合练手和实战 | — | 题库沉淀 + 实战演练 |
| 8 | offer压题 | 功能全面、多端兼容 | 回答定制化不够 | 跨设备、全流程覆盖 |
| 9 | 俄莱面AI | 功能全、比试模式强、适合练手 | 面试场景优势不突出 | 双人对战/比试训练 |

---

## 二、可落地优势 → 集成方案（★=已落地）

### ★1. 定制题库（offer毕/面星/offer压题/面试猫）
**优势**：把 AI 生成题/自定题沉淀为个人题库，随时组面。
**集成（本项目已实现 v1.28.0）**
- 后端：`POST /api/favorite/add`（手动加自定义题）、`POST /api/favorite/bank/start`（收藏→新会话）、`POST /api/favorite/toggle`（收藏快照）
- 前端：收藏夹页「从收藏发起面试」「手动加题」；`InterviewView` 支持 `?sessionId=` 载入
- 端到端验证通过（加题→list→bank/start→返回会话题目）

```java
// 后端 bank/start —— 复用现有会话/答题/评分链路
@PostMapping("/bank/start")
public Result<Map<String, Object>> startFromBank(@RequestBody Map<String, Object> req) {
    String userId = currentUserId();
    List<Long> ids = parseIds(req.get("favoriteIds"));
    if (ids.isEmpty()) return Result.error(400, "请选择至少一道收藏题目");
    List<FavoriteQuestionEntity> chosen = favoriteService.listByUser(userId).stream()
            .filter(f -> ids.contains(f.getId())).toList();          // 归属校验防 IDOR
    if (chosen.isEmpty()) return Result.error(404, "未找到有效的收藏题目");
    InterviewSessionEntity session = sessionService.createSession(userId, "收藏题库", null);
    List<InterviewQuestionEntity> qs = chosen.stream().map(f -> InterviewQuestionEntity.builder()
            .sessionId(session.getSessionId()).question(f.getQuestion())
            .category(f.getCategory()).difficulty(f.getDifficulty())
            .referenceAnswer(f.getReferenceAnswer()).build()).toList();
    var saved = sessionService.saveQuestions(session.getSessionId(), qs);
    return Result.success(Map.of("sessionId", session.getSessionId(), "questions", saved));
}
```
```ts
// 前端收藏夹页：全部收藏一键组面
const res = await api.post('/api/favorite/bank/start', { favoriteIds: items.value.map(f => f.id) })
router.push({ path: '/interview', query: { sessionId: res.sessionId } })
```

### ★2. 语音技术术语纠正（面试猫/GPT面试AI助手/面试精灵）
**优势**：ASR 对英文技术术语误转写纠正。
**集成（已落地 v1.28.0）**：`frontend/src/utils/speech.ts` 新增纯函数 `correctTechTerms`，接入 `createSpeechRecorder` 的 `onFinalText` 与指标计算。
```ts
export function correctTechTerms(text: string): string {
  let out = text ?? ''
  for (const [re, to] of TECH_TERM_FIXES) out = out.replace(re, to)  // transformer→Transformer、变换者→Transformer...
  return out
}
```

### ★3. 数据治理（字段归一化/去重/自动分类 —— 覆盖工具"基于真实经历检索+题库丰富"的准确性基础）
**已落地 v1.27.0**：`JobFieldNormalizer`（degree/experience 归一化）、`doRefresh` 跨数据源去重、`JobClassifyService` 用免费模型自动分类补全。

### ★7. 简历→岗位双向匹配（offer毕"基于真实经历检索"延伸落地 v1.29.0）
**优势**：以真实简历为底，推荐高吻合岗位，减少海投。
**集成（v1.29.0）**：`JobMatchService` 技能/学历命中打分；`POST /api/jobs/match`；前端「简历匹配推荐」按钮粘贴简历即出分、命中标签、一键进入岗位详情。
```ts
const res = await api.post('/api/jobs/match', { resumeText })
// items[].matchScore / matchedSkills 驱动岗位卡片「匹配 xx 分」+技能标签
```

### 4. 简历 RAG 强化出题 + 针对性追问链（offer毕/面星/面试猫/牛客"智能追问链"）
**已落地 v1.28.0**：
- `InterviewService.generateFollowUp(question, userAnswer, resumeText)`：用免费模型链基于回答+简历生成一道深挖盲区/细节的追问
- 新增 `POST /api/interview/followup`，纳入 `AiConcurrencyGuard` 并发闸门 + `PromptSanitizer` 防注入
```java
// 后端：针对性追问（追问链加深）
@PostMapping("/followup")
public Result<String> followUp(@RequestBody Map<String, String> request) {
    if (request.getOrDefault("question", "").isBlank()) return Result.error(400, "question 不能为空");
    return Result.success(interviewService.generateFollowUp(
        request.getOrDefault("question",""),
        request.getOrDefault("userAnswer",""),
        request.getOrDefault("resumeText","")));
}
```
```ts
// 前端可在答题后调用，把返回的追问追加进当前会话题目继续作答
const q = await api.post('/api/interview/followup', { question, userAnswer, resumeText })
// 期望：贴近简历、不重复原题、下钻薄弱点的下一道题
```

### ★8. 多模态图片输入（面试猫"图片识别"落地 v1.30.0）
**优势**：回答时可上传代码截图/白板草图/证书，AI 结合图片综合评估。
**集成（v1.30.0）**：后端 `POST /api/interview/upload-image` 上传至 Supabase 返回公开 URL，`POST /api/interview/evaluate` 支持可选 `imageUrl` 走 `Media` 多模态；前端答题区「上传附图」+ 缩略图/移除，切题自动清除。
```ts
// 上传 → 拿 URL → 携带进评估
const up = await api.post('/api/interview/upload-image', formData)  // {url}
await api.post('/api/interview/evaluate', { question, userAnswer, imageUrl: up.url })
```
```java
// 后端：PromptUserSpec.media 组装图片 visual 输入（OpenAI 兼容 image_url）
chatClient.prompt().user(u -> u.text(prompt).media(new Media(detectMimeType(url), URI.create(url))))
```
**多模态模型覆盖**：降级链三档均原生支持图像理解——
- 主模型 **GLM-5.3-Flash**（B.AI）：GLM-5 系列首个原生多模态，输入支持图像/视频/文件（2026-08-26 上线）
- 次模型 **Qwen3.x-Flash**（B.AI）：输入支持图像
- 兜底 **agnes-2.5-flash**：支持图像 URL 视觉理解

三档任一命中均能看图评估；`evaluateAnswerWithImage` 对空 imageUrl 自动退化纯文本。

### ★9. 智能体联网搜岗（v1.31.0）
**优势**：Career Copilot 智能体可联网搜索各大招聘平台的全国实时岗位，不局限于本地种子/入库数据与个别地区。
**集成（v1.31.0）**
- 新增 `WebJobSearcherService`：jsoup 实时抓取 BOSS直聘/智联/拉勾/前程无忧公开招聘搜索页，全国范围，标准化返回标题/企业/地点/薪资/链接
- 智能体注册新工具 `searchWebJobs`（keyword + 可选 location），接入 ReAct 循环；用户要求"最新/全网/全国岗位"时模型自动触发
- **稳定性兜底**：联网抓取无可用源或失败时自动降级到本地聚合岗位库，保证智能体正常回复
```java
// 后端：联网搜索工具（多源 + 超时 + 空降级）
List<WebJob> jobs = webJobSearcherService.searchWeb(keyword, location);
// jobs 为空 → fallbackToLocalJobs(kw, loc) 回退本地库，回复始终可用
```

### 5. 开源底座对齐（interview help）
**参考**：本项目已 Spring Boot + Vue，模块齐备；可按需对照其功能清单补齐缺口。

---

## 三、适用场景地图

| 用户诉求 | 落点 |
|---|---|
| 企业真实经历定制练习 → | 简历RAG出题 + 定制题库（★） |
| 语音作答与表达提升 → | ASR + 语速/停顿 + 术语纠正（★） |
| 题目沉淀与错题重练 → | 收藏夹 + 错题本 + 从题库组面（★） |
| 筛选真实在招岗位 → | 招聘广场 + 热招速递 + 每小时扩充（★） |
| 向智能体问"最新/全国岗位" → | 智能体联网搜岗 searchWebJobs（★） |
| 简历快速锁定高吻合岗位 → | 简历岗位匹配推荐（★） |
| 图片/白板/证书多模态 → | 多模态附图评估（★） |

## 四、不做项（明确边界）
- NPC human/面试狗/interview help 的「隐蔽耳麦/实时偷听对面给答案」属面试作弊，**不实现**；以合规的面试前中自练、结束后复盘替代（本项目已具备三维评分复盘）。

---

## 五、验证记录（真实）
- 后端单测 **305/305** 全过；前端 vue-tsc 0 错误；
- 定制题库生产端到端：`/add`→200 id=1 → `/favorite/list` total=1 → `/favorite/bank/start`→sessionId + 1 题(MEDIUM) 全部通过。
- 语音术语纠正单测 3/3；招聘广场归一化 meta 已收敛为干净枚举。
- 简历岗位匹配（v1.29.0）：`JobMatchServiceTest` 3/3（技能/学历命中 + 排序）；`JobFieldNormalizerTest` 2/2；`JobPlatformAdapterTest` 6/6（含热招速递广州 Java 实习覆盖）全过。
- 多模态附图（v1.30.0）：`evaluateAnswerWithImage` 单测 2/2（带图走 media、空图退化纯文本）；`InterviewControllerTest` 新增 imageUrl 用例 1/1；模型升级 agnes-2.5-flash。
- 智能体联网搜岗（v1.31.0）：后端单测 **305/305** 全过；`AgentToolsTest` 新增 `searchWebJobs` 3/3（联网命中、联网空降级本地库、双空引导文案）；`WebJobSearcherServiceTest` 智联 SSR 解析 3/3；公开招聘站点可达性实测 OK（智联 ssp 岗位 JSON 21 条、拉勾/51job 可达）。
- 智能体稳定性（v1.31.1）：`AgentService.callModel` 纳入 `AiConcurrencyGuard` 全局并发闸门，修复智能体模型调用不受限流保护导致的偶发"无回复"；前端新增「停止生成」+ 冷启动唤醒重试。
- 模型能力释放（v1.31.2）：系统提示词放开通用问答边界（知识问答/元问题直接作答，不强行依赖工具）；收尾回答 `maxTokens 1500→2500`、`temperature 0.4→0.5`，避免长回答截断；ReAct 轮次 6→8 支持多步推理；模型调用自动重试一次；流式分块改按行，长回答更顺滑。
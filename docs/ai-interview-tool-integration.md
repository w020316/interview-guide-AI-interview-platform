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

### 5. 多模态输入（面试猫"图片识别"）
**backlog**：答题/自我介绍支持上传图片（白板草图/证书），AI 结合图片评估（待实现）。

### 6. 开源底座对齐（interview help）
**参考**：本项目已 Spring Boot + Vue，模块齐备；可按需对照其功能清单补齐缺口。

---

## 三、适用场景地图

| 用户诉求 | 落点 |
|---|---|
| 企业真实经历定制练习 → | 简历RAG出题 + 定制题库（★） |
| 语音作答与表达提升 → | ASR + 语速/停顿 + 术语纠正（★） |
| 题目沉淀与错题重练 → | 收藏夹 + 错题本 + 从题库组面（★） |
| 筛选真实在招岗位 → | 招聘广场 + 热招速递 + 每小时扩充（★） |
| 面对题目深度追问 → | 追问链加深（待实现） |
| 图片/白板多模态 → | 多模态输入（待实现） |

## 四、不做项（明确边界）
- NPC human/面试狗/interview help 的「隐蔽耳麦/实时偷听对面给答案」属面试作弊，**不实现**；以合规的面试前中自练、结束后复盘替代（本项目已具备三维评分复盘）。

---

## 五、验证记录（真实）
- 后端单测 **291/291** 全过；前端 vue-tsc 0 错误；
- 定制题库生产端到端：`/add`→200 id=1 → `/favorite/list` total=1 → `/favorite/bank/start`→sessionId + 1 题(MEDIUM) 全部通过。
- 语音术语纠正单测 3/3；招聘广场归一化 meta 已收敛为干净枚举。
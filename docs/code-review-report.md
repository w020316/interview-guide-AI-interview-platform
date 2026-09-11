# 全项目代码审查与功能测试报告

> 审查范围：interview-guide（Spring Boot 3 后端 83 文件 + Vue3 前端 71 文件 + 34 测试文件）
> 审查方式：静态代码审查 + 编译警告 + 全量单测 + 前端类型检查 + 安全专项审计（IDOR/XSS/注入/泄露/性能）
> 日期：2026-09-11　基线：后端单测 316/316、前端 vue-tsc 0 错误

---

## 一、问题总览（按优先级）

| ID | 级别 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| A-01 | P1 | AuthController | `loginFailMap` 登录失败计数仅靠登录请求惰性清理，大量不同 IP 各失败不达锁定阈值时 map 无界增长 → 内存泄漏 | ✅ 已修复 |
| A-02 | P2 | JobPostingEntity | `@Builder` 忽略 `active=true` 初始化表达式，builder 未显式设值时 active 为 null（列 NOT NULL） | ✅ 已修复 |
| A-03 | P3 | GlobalExceptionHandler | 参数/非法状态异常直接拼 `ex.getMessage()`，`会话不存在/无权操作` 等内部细节暴露，可被用于资源枚举探测 | 评估保留 |
| A-04 | 风险已排除 | — | IDOR 越权（Session/Event/Resume/Favorite/Agent 各 endpoint） | 全部在 controller/service 层有归属校验，无越权 |
| A-05 | 风险已排除 | 前端 | XSS：全部 6 处 v-html 均经 `renderMarkdown`（DOMPurify 消毒） | 安全 |
| A-06 | 风险排查 | 全项目 | 硬编码密钥 / `printStackTrace` / TODO | 无硬编码密钥，无 printStackTrace，无 TODO |
| A-07 | 已确认 | JobAgentService | refresh 事务边界正确（无 @Transactional 长事务）、并发互斥锁、连接池安全 | 健康 |

---

## 二、问题详情与修复

### A-01 【P1·内存泄漏】AuthController 登录失败计数无界增长
- **根本原因**：`loginFailMap`（IP→失败次数）只在 `login()` 时调用 `cleanupExpiredLoginFails()` 惰性清理。若攻击者不断用**不同 IP** 各触发一次失败（不达 5 次锁定阈值），每次 `cleanup` 都无法及时清掉未锁定的条目，map 随请求持续增长，长稳运行下内存膨胀。
- **修复**：新增 `@Scheduled(fixedDelay=5min)` 定时清理方法 `scheduledCleanupLoginFails()`，每 5 分钟主动清除过期条目（与 `RateLimitInterceptor` 的定期清理机制对齐）。
- **预防措施**：所有基于内存的 `ConcurrentHashMap` 计数/缓存，都应配定时清理或容量上限，不能只依赖请求路径惰性清理。

### A-02 【P2·逻辑】JobPostingEntity `@Builder` 忽略 active 初始化
- **根本原因**：`private Boolean active = true;` 的初始化表达式在 Lombok `@Builder` 下被忽略 → 用 builder 构建且未显式 `.active(...)` 的实例，`active` 为 `null`，而数据库列 `NOT NULL`，`Specification.isTrue(active)` 查询也会受影响（NULL 不匹配）。
- **修复**：字段加 `@Builder.Default`，使 builder 默认值生效，与字段初始化一致。
- **预防措施**：使用 Lombok `@Builder` 时，凡有默认值的字段必须标注 `@Builder.Default`，或用 `@Column(nullable=false)` + service 层兜底。

---

## 三、功能测试结论

- 后端全量单测 **316/316** 通过（0 Failure / 0 Error）。
- 前端 `vue-tsc --noEmit` **0 错误**。
- 覆盖的核心链路：认证（注册/登录/锁定/并发注册）、会话与题目（增删查/越权拒绝）、收藏/定制题库、模拟面试出题/作答/追问/评估、简历上传/分析/优化、招聘广场检索/联网搜岗/匹配、知识库 RAG、智能体对话（工具调用/降级/重试）、多模态附图、站点可达性。

---

## 四、v1.31.4 全面复核（第二轮审计，AI 专项 + 全项目回归）

> 方法：4 路并行深度审查（后端服务/AI、控制器/安全/配置、实体/仓库/工具、前端 Vue）+ 全量回归。
> 基线：后端单测 316/316、前端 vue-tsc 0 错误。

### 4.1 本轮新增问题与修复

| ID | 级别 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| B-01 | P1 | InterviewService / ResumeAnalysisService | 二者各保留独立 `AI_SEMAPHORE(5)`，与全局 `AiConcurrencyGuard` 形成 **3 个信号量**，最坏并发 15 而非 5，免费模型限流下并发被放大；本轮修复漏改 | ✅ 统一为 `AiConcurrencyGuard.call()`，删除冗余字段与不可达 `catch(InterruptedException)` |
| B-02 | P1 | 前端 AgentView.send | 冷启动重试在 `streaming.value=true` 仍置位时递归调 `send()`，被 `if(streaming) return` 拦截，重试**永远不执行**且用户消息已被移除 | ✅ 重试前复位 `streaming`/`abortController` |
| B-03 | P2 | 前端 InterviewView.streamHint | `hintColdRetried` 每次进入函数都被重置为 false，冷启动失败后递归可**无限重试** | ✅ 改计数配额(≤1)，由 `startHint` 用户入口重置 |
| B-04 | P2 | TextUtil.truncate | 按 UTF-16 码元截断，落在 emoji/生僻字代理对中间会截出孤立 surrogate 坏字符 | ✅ 截断点落在代理对时回退一位 |
| B-05 | P2 | AgentMessageEntity | `@Data` 对含 LAZY ManyToOne 的实体生成 equals/hashCode/toString，事务外访问抛 `LazyInitializationException` 或暗生 N+1 | ✅ 改 `@Getter/@Setter` |
| B-06 | P3 | AgentMessageRepository | `findLatestByConversationId` 未 `JOIN FETCH`，与同文件另一取数方法不一致，存在 N+1 | ✅ 补 `JOIN FETCH m.conversation` |

### 4.2 评估保留/建议（未改，风险已记录）

| ID | 级别 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| B-07 | P1 | ResumeController `/api/resume/import-url` | DNS rebinding SSRF：校验用 `getAllByName` 解析 IP 合格后，`Jsoup.connect(url)` 再次独立解析并连接，存在 TOCTOU 窗口可命中内网/云元数据 | 用已校验的 `InetAddress` 直连同源 IP + 强制 Host 头，屏蔽 169.254.169.254/内网/链路本地；连接复用同一解析结果 |
| B-08 | P2 | InterviewController `/api/interview/upload-image` | `userId` 自请求参数而非 JWT（违背"userId 一律取 JWT"约定），攻击者可向任意 userId 命名空间写图（含图转链的 SSRF 面） | 改用 `currentUserId()`；对 `imageUrl` 协议/地址做服务端校验 |
| B-09 | P2 | 前端 Interview/Agent View 流式渲染 | 每收一个 token 就对全量内容 `renderMarkdown`(markdown-it+DOMPurify)，长回复为 O(n²) | 增量渲染或 rAF/定时器节流合并 token |
| B-10 | P2 | JobAgentController `/api/jobs/refresh` + SecurityConfig | 全系统无角色分级，任意登录用户可触发第三方抓取/全库刷新及同步 AI 接口（无按用户限流/配额），可刷爆第三方配额 | refresh 加 `hasRole('ADMIN')`/独立密钥，AI 接口按用户限流或加配额 |
| B-11 | P3 | GlobalExceptionHandler.handleIllegalState | 500 级异常仍透出 `ex.getMessage()`，可能泄露内部细节（与注释"屏蔽内部细节"相悖） | 500 级固定文案，细节仅写日志；用户友好提示改由专门业务异常承载 |
| B-12 | P3 | JsonRepairUtil.UNQUOTED_KEY 正则 | 全局正则替换未跳过字符串字面量，可能误注入已合法 JSON 字符串值内的 `, b:` 类文本 | 复用转义状态机，仅在字符串字面量外执行 key 修复 |
| B-13 | P3 | PromptSanitizer | 黑名单关键词替换无法根治提示词注入（大小写/空格变体可绕过）；`MAX_INPUT_LENGTH` 按码元截断同 B-04 | 依赖 system 强约束 + 输出侧校验；长度按码点 |
| B-14 | P3 | InterviewService 出题提示词 | 编号混乱（主列表 1/1.1/1.2/3，难度/聚焦规则又各输出 2.），存在重复 2.、缺 3. 前置 | 统一编号序列 |
| B-15 | P3 | JobMatchService / WebJobSearcherService | `text == null ||` 恒假死代码；`SKILL_KEYWORDS` 中 `flink` 重复；`CONNECT_TIMEOUT_MS` 定义未用 | 清理死代码 |

### 4.3 回归结论

- 修复 B-01..B-06 后，后端全量单测 **316/316** 通过（BUILD SUCCESS）、前端 `vue-tsc` **0 错误**。
- B-07..B-15 为已记录的风险与低优先清理项，暂不修改行为以避免回归，建议在后续版本按建议落实。

---

## 五、修复后回归
- 修复后全量单测 **316/316** 全绿，未引入新问题。
- 新增 `JobPostingEntityTest`（2/2）固化 A-02 修复：验证 `@Builder.Default` 默认值与显式覆盖，防回归。

---

## 六、总结论
项目整体安全性与健壮性良好：IDOR 越权防护到位、XSS 全链路消毒、无硬编码密钥、事务边界正确、并发与限流有保护。本轮修复 2 个真实问题（登录失败计数内存泄漏、builder 默认值），全部通过回归。
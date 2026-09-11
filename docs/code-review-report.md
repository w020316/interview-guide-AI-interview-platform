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

## 四、修复后回归
- 修复后全量单测 **316/316** 全绿，未引入新问题。
- 新增 `JobPostingEntityTest`（2/2）固化 A-02 修复：验证 `@Builder.Default` 默认值与显式覆盖，防回归。

---

## 五、结论
项目整体安全性与健壮性良好：IDOR 越权防护到位、XSS 全链路消毒、无硬编码密钥、事务边界正确、并发与限流有保护。本轮修复 2 个真实问题（登录失败计数内存泄漏、builder 默认值），全部通过回归。
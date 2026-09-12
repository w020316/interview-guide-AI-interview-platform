# 网站可用性保障方案（Uptime & Availability Plan）

> 版本：1.32.0 ｜ 日期：2026-09-13 ｜ 适用范围：AI 智能面试辅助平台（前端 Cloudflare Pages + 后端 Render + Supabase PostgreSQL + Upstash Redis）

本方案覆盖四层保障：**前端性能**、**后端稳定性**、**监控告警**、**安全与应急**，并配套完整测试流程与维护清单，目标是让站点在免费托管资源约束下持续、稳定可访问。

---

## 一、架构与关键依赖（保障范围）

| 组件 | 托管 | 用途 | 故障影响 |
|------|------|------|----------|
| 前端 Vue3 SPA | Cloudflare Pages | 用户界面 | 全站不可访问 |
| 后端 Spring Boot | Render（免费层，新加坡） | 业务 API / SSE / AI | 全部功能不可用 |
| PostgreSQL | Supabase | 业务数据 | 登录/数据接口不可用（后端仍可启动） |
| Redis | Upstash（TLS） | AI 响应缓存 / JWT 黑名单 | 仅影响缓存与登出吊销（服务已降级） |
| AI 模型 | B.AI / Agnes（免费额度） | 简历/面试/岗位/智能体 | 仅 AI 功能降级为兜底回答 |

**关键设计**：后端启动不依赖 Redis（配置 `RedisConfig` 容错回退），数据库不可达时业务接口返回友好错误而非崩溃；AI 模型失败自动降级到下一顺位或本地兜底。

---

## 二、前端性能优化（已实施）

1. **构建产物压缩**：Vite 生产构建 `sourcemap: false`，`manualChunks` 拆分 vue-vendor / element-plus / markdown，减少首屏加载体积。
2. **静态资源缓存**：`public/_headers` 中 `/assets/*` 强缓存 1 年（`immutable`，Vite 产物含内容指纹）；HTML `no-cache` 确保版本即时更新；favicon 独立强缓存。
3. **安全响应头**：HSTS（1 年 + preload）、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy`、`Permissions-Policy`（禁定位/麦克风/摄像头）；`/api/*` 响应 `no-store` 防缓存泄漏。
4. **SPA 路由兜底**：`_redirects` 全路径回退 `index.html`，刷新/深链接不 404。
5. **预连接后端**：`index.html` 增加 `<link rel="preconnect">` 到后端域名，减少跨域 TLS 握手延迟。
6. **SEO / 元信息**：补充 description、theme-color、format-detection。
7. **响应式适配**：设计系统 v4 已内置多分辨率断点与暗色模式；favicon 补齐（此前缺失产生 404 请求）。

---

## 三、后端稳定性保障（已实施）

1. **优雅停机**：`server.shutdown: graceful` + `timeout-per-shutdown-phase: 20s`——部署/重启时等待在途请求完成，降低 5xx。
2. **线程模型适配**：Tomcat `max=50 / min-spare=4 / accept-count=100`，适配免费层 512MB 内存，避免线程栈挤占堆。
3. **数据库连接池**：HikariCP `maximum-pool-size=2`、`connection-test-query: SELECT 1`、`validation-timeout=5s`、`leak-detection-threshold=30s`——防坏连接与连接泄漏。
4. **全局异常处理**：`GlobalExceptionHandler` 统一响应，`BusinessException` 面向用户返回可重试文案，内部异常不透出细节；超时/数据库/AI 异常均有兜底。
5. **AI 并发闸门**：`AiConcurrencyGuard` 全局 5 许可，杜绝免费模型限流下并发放大。
6. **深度健康体检**：新增 `GET /api/health`（匿名可访问）——数据库 `SELECT 1`、Redis `PING`、JVM 堆内存与线程数、运行时长；单项异常只降级字段，接口始终 200，供监控判定。
7. **输入校验与防护**：SSRF 防护（`SsrUrlValidator`）、提示词注入清洗（`PromptSanitizer`）、限流（IP 10/min + 用户级 30/min）、上传类型/大小校验（≤10MB）。

---

## 四、监控与告警（已实施）

1. **保活 + 健康监控**：GitHub Actions `Keep Render Backend Warm` 每 10 分钟：
   - ping `/api/info`（唤醒免费实例，双次重试容忍冷启动 100s）；
   - 深度体检 `/api/health`：解析 `database` / `redis` 状态，任一非 `UP` 即 Run 失败 + 邮件告警。
2. **内置指标**：Spring Boot Actuator `/actuator/health`、`/actuator/info`（公开）；`/actuator/metrics` 与 Prometheus 指标（`MetricsConfig`：AI 调用计数/耗时、缓存命中）需认证。
3. **管理后台系统指标**：`/admin`（管理员）实时查看 AI 调用、缓存、SSE 并发、JVM 内存。
4. **告警通道**：
   - GitHub Actions Run 失败 → 仓库邮件通知（保活/CI）；
   - Dependabot 依赖漏洞 → 自动 PR + 通知；
   - Render Dashboard Events/Logs → 部署失败事件可见。

> 注：免费方案下不引入外部 uptime 服务商；如需更高 SLA 可后续接入 UptimeRobot（免费 50 个监视器）对前端与后端域名做 5 分钟级探活。

---

## 五、安全加固（已实施）

1. **依赖漏洞扫描**：
   - Dependabot（`.github/dependabot.yml`）：npm / Maven / GitHub Actions 每周扫描并自动开修复 PR；
   - CI 增加 `npm audit --audit-level=high`：高危漏洞阻断构建。
2. **安全响应头**：见第二节第 3 条。
3. **应用层防护**（此前已落地）：JWT 无状态鉴权（含 ROLE_ADMIN）、CORS 白名单、SSRF 拦截、提示词注入清洗、IP/用户限流、上传白名单、异常不透出内部细节。
4. **敏感信息**：密钥全部走环境变量（Render secret 类型），不落仓库。

---

## 六、故障应急预案（Runbook）

### 通用流程
1. **发现**：保活 Run 失败 / 管理后台指标异常 / 用户反馈。
2. **初步判断**：访问 `https://interview-guide-backend.onrender.com/api/health` 与前端页面，判断是前端、后端、数据库、Redis 或 AI 问题。
3. **处置**：按下表对应场景执行。
4. **恢复确认**：`/api/health` 各组件 `UP` + 前端登录可用。
5. **复盘**：记录到本文档与 changelog，防止复发。

### 场景表

| 级别 | 场景 | 症状 | 处置 |
|------|------|------|------|
| P0 | 后端停机/崩溃循环 | /api/health 超时；Render Events 大量 `Exited with status 1` | ① Render Dashboard → interview-guide-backend → Logs 查启动堆栈；② 按错误修复并推送 main（Render 自动部署）；③ 若为容器内启动失败，`Manual Deploy → Clear build cache & deploy` |
| P0 | 数据库不可达 | /api/health `database=DOWN`；登录/列表接口 500 | ① Supabase Dashboard 查连接池/健康；② 核对 `DATABASE_URL`（含用户名密码）；③ 数据库恢复后后端自动恢复（Hikari 自动重连） |
| P1 | Redis 不可用 | /api/health `redis=DOWN`（仅影响缓存与登出吊销，鉴权不受影响） | ① Upstash Dashboard 核对 `REDIS_URL`/TLS；② 修复后无需重启（惰性重连）；③ 期间 AI 缓存降级为直查，登录正常 |
| P1 | AI 模型限流/故障 | AI 接口报"所有模型均调用失败"或超时 | ① 自动降级链已兜底（B.AI → Qwen → Agnes）；② 检查各 API Key 额度；③ 等待限流窗口后重试 |
| P1 | 前端不可访问 | pages.dev 打不开 | ① Cloudflare Pages Dashboard 看构建/部署；② 重新触发 Deploy；③ 检查 `_redirects`/`_headers` |
| P2 | 部署后行为未更新 | 前端/后端版本不符 | ① 前端：Pages 构建成功但强缓存 → 确认 HTML `no-cache`；② 后端：Render 未自动重拉 → Manual Deploy |
| P2 | 依赖漏洞（Dependabot） | Dependabot PR | ① 审阅 PR；② 合并触发 CI；③ CI 绿后自动部署 |

### 关键联系与入口
- Render Dashboard：https://dashboard.render.com （服务 `interview-guide-backend`，srv-d94i7ocvikkc73cfjli0）
- Cloudflare Pages：https://dash.cloudflare.com → interview-guide-ai-interview-platform
- Supabase：项目控制台 → Database / Health
- Upstash Redis：控制台 → lawai-redis / trusty-monarch-113421
- 后端健康：https://interview-guide-backend.onrender.com/api/health
- 前端：https://interview-guide-ai-interview-platform.pages.dev

---

## 七、测试流程（已建立）

| 层级 | 手段 | 覆盖 | 触发 |
|------|------|------|------|
| 单元测试 | 后端 JUnit 337 例；前端 Vitest（覆盖率阈值 80%） | 工具类/服务/控制器/安全/限流/异常 | CI（push/PR） |
| 集成测试 | `@WebMvcTest` 切片 + `@SpringBootTest` 完整上下文冒烟（`ApplicationContextSmokeTest`） | 装配正确性、鉴权链、健康接口 | CI |
| 压力测试 | `scripts/loadtest.mjs`（Node 原生并发压测） | `/api/health` 等公开端点的 P50/P95/P99、成功率、状态码分布 | 上线前后手动执行 |
| 多网络验证 | 浏览器实测（桌面/移动视口）+ 保活监控持续探活 | 不同网络环境下的可访问性 | 每次发布 |

### 压测脚本用法
```bash
# 默认打生产 /api/health：并发 30，共 300 请求
node scripts/loadtest.mjs
# 自定义目标与规模
node scripts/loadtest.mjs --url https://interview-guide-backend.onrender.com/api/info --concurrency 50 --requests 500
```

### 发布回归清单
1. `mvn test`（后端全量，含上下文冒烟）；
2. `npm run coverage`（前端覆盖率）+ `vue-tsc --noEmit`（类型）+ `npm run build`；
3. 压测公开健康端点确认 P95 达标；
4. 浏览器实测登录 → 简历/面试/岗位/智能体主流程；
5. 确认保活 Run 连续 2 次成功。

---

## 八、持续维护与记录

- 所有正确的优化/修复必须同步更新本文档与 `frontend/src/changelog.ts`（前端版本弹窗）。
- 每次发布推送 `main` 触发 CI + Render 自动部署 + Cloudflare Pages 自动部署。
- 本方案按需迭代：监控阈值、压测规模、应急预案场景随业务演进补充。

---

## 附：生产事故复盘记录

### 2026-09-13 · 生产 Redis 指向已删除实例（P1，已修复）
- **症状**：`/api/health` `redis=DOWN`；AI 缓存与登出令牌吊销名存实亡（Redis 不可达时黑名单 fail-open）。
- **根因**：Render 环境变量 `REDIS_URL` 仍指向已删除的 `interview-redis`（`trusty-monarch-113421.upstash.io`，DNS 无法解析）；有效实例为 `lawai-redis`（`improved-rabbit-178109.upstash.io`）。
- **处置**：Upstash 控制台读取有效实例完整连接串（`rediss://default:<token>@improved-rabbit-178109.upstash.io:6379`）→ Render Environment 更新 `REDIS_URL` → `Save, rebuild, and deploy` → 验证 `/api/health` `redis=UP`。
- **预防**：深度体检将 Redis 纳入监控告警（本方案第四节第 1 条），后续此类失效会在 10 分钟内触发 Run 失败 + 邮件；Upstash 实例删除前需先更新 Render 配置。

### 2026-09-13 · 探活端点被限流误拦（P2，已修复）
- **症状**：并发压测 `/api/health` 出现 68 个 429，成功率仅 77%。
- **根因**：`RateLimitInterceptor`（IP 10/min）覆盖全部 `/api/**`，无状态探活端点也被限流。
- **处置**：`WebMvcConfig` 将 `/api/auth/**`、`/api/health`、`/api/info` 排除出限流拦截器。
- **验证**：300 并发请求成功率 100%，P50 1.7s / P95 2.9s / P99 3.5s（免费层新加坡实例）。

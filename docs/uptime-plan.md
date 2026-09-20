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

1. **保活 + 健康监控**：GitHub Actions `Keep Render Backend Warm` **每 5 分钟**（v1.34.0 由 10 分钟收紧）：
   - ping `/api/info`（唤醒免费实例，8 分钟耐心重试循环容忍冷启动）；
   - 存活校验 `/api/health` 返回 `status: UP`，异常即 Run 失败 + 邮件告警。
   - 收紧到 5 分钟的原因：Render 休眠阈值 15 分钟，而 GitHub 定时任务在高峰期
     **常有数分钟延迟**（实测可达 5~15 分钟），原 10 分钟间隔叠加延迟后可能越过阈值。
     本仓库为 public，Actions 分钟数不限量，加密无成本。
   - ⚠️ 已知限制：GitHub 对「60 天无提交活动」的仓库会自动停用定时工作流；
     长期停更后需手动在 Actions 页重新启用。
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

### 2026-09-19 · 冷启动导致登录停滞 / 认证无响应（P0，已修复）
- **实测基线**：线上复测 `GET /actuator/health` 首个请求 **98.1s** 返回 200（冷启动），
  随后的 `/api/info` 2.7s、`/api/health` 14.1s（已热）。即**冷启动耗时约 1.5 分钟**。
- **症状**：长时间闲置后首次访问——登录界面点「登录」后长时间无响应；偶发返回
  `Request failed with status code 502` 这类无法理解的英文报错；进入内页数据全部加载失败。
- **根因（四处叠加）**：
  1. 前端 `AUTH_TIMEOUT = 90s` **小于冷启动 98s**，首次登录请求必然先超时；
  2. 应用启动阶段**没有任何预热请求**，后端只在登录请求失败后才开始被唤醒；
  3. 边缘节点冷启动期返回的 502/503/504 未被识别为「未就绪」，既不重试也不给可读文案；
  4. 幂等 GET 请求在本地超时（`ECONNABORTED`）时被排除在自动重试之外，长闲置后打开页面必报错。
- **处置**：
  - 新增 `frontend/src/utils/backendWake.ts` 冷启动唤醒器（单飞 + 反应式进度），
    `main.ts` 应用挂载前即预热、登录页挂载时二次确认，用户输入账号密码的时间覆盖大部分冷启动；
  - `AUTH_TIMEOUT` 90s → **150s**（> 实测 98s）；默认超时 60s → 90s；
  - 502/503/504 与 HTML 响应统一识别为冷启动信号，转为「后端服务未响应，可能正在冷启动」；
  - 幂等 GET 在冷启动/超时后自动唤醒并**重放一次**（唤醒预算 150s）；
  - 登录页展示「正在冷启动，已等待 Ns」实时进度，不再出现静默等待。
- **验证**：前端 262 例单测全绿（含 7 例唤醒器专项 + 冷启动分类专项）。

### 2026-09-19 · AI 配置曾可导致整站无法启动（P0，已修复）
- **症状**：若 AI 提供方环境变量缺失，站点**完全不可用**（登录、注册、健康检查全部失败），
  Render 表现为健康检查持续失败 → 实例反复重启 → 永久"冷启动"。
- **根因**：
  1. `AiConfig.fallbackChatModel` 在 `app.ai.chain` 解析不出可用提供方时**直接抛异常**，
     使整个 Spring 上下文启动失败——AI 配置与「应用能否启动」被错误地耦合在一起；
  2. `application-prod.yml` 中 `spring.ai.openai.api-key`、`app.supabase.url/service-key`、
     `spring.data.redis.url` 均无可解析默认值，任一缺失即启动失败。
- **处置**：链条为空时回退 `spring.ai.openai.*` 构造单节点模型（应用始终可启动，AI 调用失败
  统一转为 503 可读文案）；上述三项补默认值 + 启动 WARN；Storage 配置占位时显式告警。
- **验证**：新增 `AiConfigFallbackTest`（4 例）锁定「降级链为空不阻断启动」契约；
  后端 620 例全绿（含覆盖率门禁 80%）。

### 2026-09-19 · 预热探测被跨域预检拦截，导致登录白等 150s（P1，已修复，真机发现）
- **发现方式**：**Microsoft Edge 153 真实浏览器真机验证**（CDP 驱动 + 线上后端）。
  同一 URL 在页面内手动 `fetch` 返回 200，但唤醒器探测连续 80s 重试 23 次全部失败——
  这个矛盾只在真实浏览器的跨域场景才出现。
- **症状**：1.33.1 引入的后端预热器在真实浏览器中**完全失效**；更严重的是
  `LoginView.authWithRetry()` 会 `await ensureAwake()`（预算 150s），预热永不就绪 ⇒
  用户点登录要**白等满 150s** 才发出请求，比修复前的 90s 超时**更差**（回归）。
- **根因（两层）**：
  1. 探测请求带了 `headers: { 'Cache-Control': 'no-cache' }`。跨域下 `Cache-Control`
     **不属于 CORS 安全列表请求头**，浏览器强制先发 `OPTIONS` 预检；
  2. `SecurityConfig.setAllowedHeaders` 白名单不支持它 → 预检返回 **403 且不带任何
     `access-control-*` 响应头** → 浏览器直接拦截真实请求。
- **为什么单测/curl 发现不了**：单测中 axios 被 mock（且用例断言了那个错误的请求头，
  等于把 bug 固化）；curl 直发不执行浏览器预检，故返回 200。
  **只有真实浏览器跨域环境能暴露此问题**——这是真机验证不可替代的价值。
- **处置**：
  1. 前端探测移除全部自定义请求头，缓存失效改用 URL 时间戳 `?_t=`，跨域下退化为「简单请求」；
  2. 探测改用 `validateStatus: () => true`——**任何 HTTP 响应（含 4xx/5xx）都视为实例已唤醒**，
     只有网络层错误（连接被拒/超时/CORS 拦截）才判定未就绪；
  3. 后端 `setAllowedHeaders` 补 `Cache-Control` 作为防御性兜底。
- **验证**：
  - 真机复测：修复前 80s 内重试 23 次从未成功；修复后**仅 1 次探测**（89ms 发起 / 184ms 返回）即就绪；
  - 前端新增 2 例（不得携带自定义请求头、5xx 仍判定已唤醒），263 例全绿；
  - 后端新增 `SecurityConfigTest$CorsPreflight`（2 例）并做**负向验证**：
    移除修复后该用例精确复现 `Status expected:<200> but was:<403>`，证明回归防线有效。
- **注意事项（写代码时务必遵守）**：跨域接口**不要携带非 CORS 安全列表请求头**
  （`Cache-Control`/`Pragma`/`X-*` 等），否则会引入预检，而后端白名单一旦未覆盖就会静默失败。
  缓存控制优先用 URL 参数或后端响应头。


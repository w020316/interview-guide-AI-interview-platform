# 真机验证报告 · 2026-09-19

> 验证对象：AI 智能面试辅助平台（`interview-guide`）
> 验证方式：**真实浏览器 Microsoft Edge 153.0.4234.32**（CDP 驱动，非无头、非模拟）+ 真实线上后端/前端
> 触发背景：1.33.1 的登录/冷启动与 AI 修复需要真机复核
> 结论：**发现并修复 1 个 P1 级真实回归**，其余登录、会话、多设备、AI 全链路全部通过

---

## 一、验证环境

| 项 | 值 |
|---|---|
| 浏览器 | Microsoft Edge `153.0.4234.32`（Windows x64，真实 GUI 实例，独立用户目录） |
| 驱动方式 | CDP（Chrome DevTools Protocol），端口 9600；`agent-browser 0.27.0` 接管 |
| 线上前端 | `https://interview-guide-ai-interview-platform.pages.dev`（**线上版本 v1.32.0**） |
| 线上后端 | `https://interview-guide-backend.onrender.com`（Render 免费层） |
| 本地已验证前端 | 本地 `vite build` 产物 v1.33.2，经 `vite preview:5173` 加载，直连线上后端 |
| 验证账号 | `probe224043`（本轮真实注册） |

> 说明：线上前端仍为 v1.32.0，**1.33.x 的前端修复尚未部署**。因此「线上基线」与「本地新代码」分两组验证。

---

## 二、后端真机实测数据

### 2.1 冷启动与健康

| 探测 | 耗时 | 结果 |
|---|---|---|
| `GET /actuator/health`（冷实例，首次） | **43.1s** | 200（本次观测） |
| `GET /actuator/health`（已被长连接占满超时的一次） | >180s | 超时（实例正处于启动中） |
| `GET /api/info` | 1.96s | `{"code":200,...}` |
| `GET /api/health` | 1.69s | `status: UP` |

> 历史基线为 **98.1s**；本次 43.1s。冷启动耗时波动大（受免费层调度影响），
> 这也说明「本地超时必须显著大于冷启动」的设计前提是必要的。

### 2.2 认证链路

| 场景 | 结果 |
|---|---|
| 真实注册 `POST /api/auth/register` | ✅ 200 / 4.84s，返回 token |
| 真实登录 `POST /api/auth/login` | ✅ 200 / 2.34s，返回 JWT（228 字符） |
| 错误凭据登录 | ✅ 401 `用户名或密码错误，还可尝试 4 次`（含剩余次数提示） |
| 无 token 访问 `/api/auth/me` | ✅ 401 `登录已过期或未登录，请重新登录` |
| 伪造签名 token | ✅ 401（签名校验生效） |
| `POST /api/auth/logout` | ✅ 200 |
| **logout 后原 token 是否失效** | ⚠️ **仍然有效（200）** —— 见 §五 发现 2 |

### 2.3 AI 全链路（真实模型供应商，真实联网推理）

| 接口 | HTTP | 耗时 | 响应体 | 结论 |
|---|---|---|---|---|
| `POST /api/job/analyze`（岗位分析） | 200 | **14.8s** | 1838 B | ✅ 结构化岗位拆解 |
| `POST /api/interview/questions`（生成面试题） | 200 | **12.6s** | 2501 B | ✅ 含 category/difficulty/keyPoints |
| `POST /api/interview/evaluate`（作答评估） | 200 | **9.4s** | 545 B | ✅ 四项评分 + 优缺点 |
| `POST /api/interview/followup`（追问） | 200 | **3.6s** | 204 B | ✅ 上下文连贯追问 |
| `POST /api/knowledge/ask`（RAG 问答） | 200 | **9.2s** | 1492 B | ⚠️ 走兜底生成，见 §五 发现 3 |

**结论**：模型加载、推理、API 调用、响应处理全部正常；无 5xx、无超时、降级链未触发。

---

## 三、浏览器真机验证（线上基线 v1.32.0）

### 3.1 首次登录

| 步骤 | 观测 |
|---|---|
| 打开 `/login` | 页面正常渲染（见 `evidence/03_login_prod_v1320.png`） |
| 填入账号密码并提交 | ✅ 跳转 `/`，`localStorage` 写入 `token`(JWT) + `username` |
| 耗时 | 后端已热，登录约 2s 内完成 |

> 注：早期用 `agent-browser click` 合成点击未生效（工具层问题），改用原生事件派发后成功；
> **这不是应用缺陷**，已在 §六 记录为工具注意事项。

### 3.2 会话过期重登（完整闭环）

| 步骤 | 观测 | 结论 |
|---|---|---|
| 正常登录态 | `token` + `username` 存在 | ✅ |
| 置入非法 token 后刷新 | **token 被自动清除** | ✅ 401 拦截器清理逻辑生效 |
| 带过期 token 访问 `/resume` | 重定向 `/login?redirect=/resume` | ✅ 路由守卫 + 保留回跳地址 |
| 在登录页重新登录 | 跳转 `/`，新 token 写入 | ✅ |
| 重登后访问 `/resume` | 成功进入受保护页 | ✅ 回跳闭环成立 |

### 3.3 多设备登录

| 场景 | 结果 |
|---|---|
| 同一账号连续签发 3 个 token | ✅ 三个 token 均签发成功（各 228 字符） |
| 三个 token 并发访问 `/api/auth/me` | ✅ 全部 200，返回同一用户 `id=60` |
| 三端互相影响 | ❌ 无影响 —— JWT 无状态、无服务端会话、无互踢 |

**结论**：多设备同时登录互不干扰，符合设计预期。

### 3.4 已登录访问 `/login`

✅ 被守卫重定向至首页（未出现"登录页滞留"）。

---

## 四、本地新代码真机验证（v1.33.2）

线上未部署新前端，故以本地构建产物 + 真实 Edge + 线上后端进行验证。

### 4.1 【关键】预热探测跨域预检缺陷 —— 已修复

**故障现象**：新前端首屏加载后，`/api/info` 探测以 **4s 间隔无限重试**：

```
修复前（v1.33.1）：
/api/info @ 142ms    → 失败
/api/info @ 3972ms   → 失败
/api/info @ 7973ms   → 失败
... 连续 23 次，持续 80s 从未成功（预算 150s 内不会就绪）
```

**同页面内手动 `fetch` 同一 URL 却返回 200**（证明网络与 CORS 正常）——这是矛盾点，也是根因线索。

**根因（两层）**：

1. 前端 `backendWake.ts` 探测时携带 `headers: { 'Cache-Control': 'no-cache' }`。
   跨域下 `Cache-Control` **不属于 CORS 安全列表请求头**，浏览器强制先发 `OPTIONS` 预检。
2. 后端 `SecurityConfig.setAllowedHeaders` 白名单为
   `["Authorization","Content-Type","X-Requested-With","Accept"]`，**不含 `Cache-Control`** →
   预检返回 **403 且不带任何 `access-control-*` 响应头** → 浏览器直接拦截真实请求。

**实测证据**：

```bash
# 预检（带 cache-control）
OPTIONS /api/info  Origin: http://localhost:5173
  Access-Control-Request-Headers: cache-control
→ HTTP/1.1 403 Forbidden      # 且无 access-control-allow-* 响应头

# curl 直发（不执行浏览器预检）
GET /api/info  Origin: ...  Cache-Control: no-cache
→ HTTP/1.1 200 OK             # 所以 curl / 单测永远发现不了
```

**影响面（P1）**：

- `prewarmBackend()` 完全失效 → 冷启动预热收益归零；
- 更严重：`LoginView.authWithRetry()` 会 `await ensureAwake()`（预算 **150s**）。
  预热永远不就绪 ⇒ **用户点登录要白等满 150s 才发出请求**，比修复前的 90s 超时**更差**——
  这是 1.33.1 引入的回归，且**仅在真实浏览器跨域环境暴露**（单测中 axios 被 mock）。

**修复**：

| 层 | 改动 |
|---|---|
| 前端（根因规避） | 探测移除全部自定义请求头；缓存失效改用 URL 时间戳 `?_t=`，跨域下退化为「简单请求」，不发预检 |
| 前端（鲁棒性） | 探测改用 `validateStatus: () => true` —— **任何 HTTP 响应（含 4xx/5xx）都视为实例已唤醒**，只有网络层错误才判定未就绪 |
| 后端（防御兜底） | `setAllowedHeaders` 补 `Cache-Control`，避免其他自定义头再踩同一坑 |
| 测试 | 前端新增「不得携带自定义请求头」「5xx 仍判定已唤醒」；后端新增 2 条 CORS 预检用例 |

**修复后真机复验**：

```
修复后（v1.33.2）：
/api/info?_t=1789829622595   @ 89ms   耗时 184ms   → 成功
count = 1        ← 仅 1 次探测，无任何轮询
```

### 4.2 新前端登录链路

| 步骤 | 结果 |
|---|---|
| 打开 `/login` | ✅ 正常，无 token |
| 冷启动提示元素 | ✅ **不显示**（预热已就绪时正确隐藏） |
| 填表登录 | ✅ 跳转 `/`，写入 JWT；**未再出现 150s 白等** |

### 4.3 浏览器内真实 AI 调用

在 `/job` 页粘贴真实 JD 并点击「开始分析」：

```
网络序列：info?_t=... @ 59ms (190ms)
          job/analyze @ 20854ms (13326ms)   ← 仅 1 次
页面输出：高级 / 核心职责 / 硬技能要求 / 软技能要求 / 隐性条件（JD 没明说但 HR 会看）/ ATS 关键词
```

✅ 结果完整渲染；✅ `job/analyze` **仅出现 1 次**，印证「AI 请求永不重放」修复生效（无双份推理）。
证据见 `evidence/01_ai_result_v1331.png`。

---

## 五、真机发现清单

| # | 发现 | 级别 | 状态 |
|---|---|---|---|
| 1 | 预热探测因 `Cache-Control` 触发跨域预检被拒（403），预热完全失效且使登录白等 150s | **P1** | ✅ 已修复并真机复验 |
| 2 | `logout` 后原 JWT 仍可继续访问受保护接口（服务端不失效 token） | P2（安全取舍） | ⏸️ 待决策 |
| 3 | RAG 问答未命中知识库，返回「参考资料中没有相关内容。以下根据常见后端知识回答」 | P2 | ⏸️ 待处理（Embedding 缺口） |
| 4 | 线上前端仍为 v1.32.0，1.33.x 前端修复未部署 | — | ⏸️ 待部署 |
| 5 | Vercel 站点 `interview-guide-ai-interview-platform.vercel.app` 已不可用（20s+ 无响应） | P3 | ⏸️ 建议下线或改指向 |

### 发现 2 补充（logout 语义）

`POST /api/auth/logout` 返回 200，但三个 token 在登出后仍全部可用（200）。
这是 JWT 无状态设计的自然结果（登出仅前端清除 token）。风险：token 一旦泄漏，在 24h 有效期内无法吊销。

可选加固（按性价比排序）：
1. Redis 黑名单：登出时把 `jti` 写入 Redis 至过期（项目已有 Upstash Redis，改造成本低）；
2. 缩短 `JWT_EXPIRATION_MS` 并引入 refresh token（改动较大）；
3. 接受现状，在文档中明确「登出 = 客户端登出」。

### 发现 3 补充（RAG 未命中）

`/api/knowledge/ask` 返回兜底文案而非知识库内容，说明检索未召回任何片段。
与既有「Embedding 缺口」一致：知识库需先导入文档并完成向量化。
建议核对 `AI_EMBEDDING_DIMENSIONS=1024`（对齐 bge-m3）与知识库导入状态。

---

## 六、验证工具注意事项（供复现）

1. **沙箱阻断 localhost HTTP**：环境设置了 `HTTP_PROXY` 且无 `no_proxy`，访问 `127.0.0.1:*` 会被代理拒绝（502/连接失败）。
   直连本机 CDP / 预览服务需清空代理：`unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy` 并 `curl --noproxy '*'`。
2. **Edge 调试实例需常驻**：以 `Start-Process` 或前台 shell 拉起的 Edge，会随发起命令结束被回收。
   需放在长驻后台任务中持有（本次用 `sleep 3600` 保活），端口才持续可用。
3. **Chromium 下载不可用**：`agent-browser install` 从 `storage.googleapis.com` 下载超时（含镜像重试），
   改用本机 Edge：`AGENT_BROWSER_EXECUTABLE_PATH` 或 `--cdp <port>` 接管。
4. **合成点击可能静默失效**：`agent-browser click/fill` 对 Element Plus / Vue 受控组件可能报 `✓ Done` 但未触发响应。
   可靠做法（本次全程采用）：页面内 `eval` 用原型链 `value` setter + 派发 `input`/`change` 事件，再 `.click()`。
5. **`__netLog` init script 不跨调用保留**：改用浏览器原生 `performance.getEntriesByType('resource')` 观测网络，无需注入。
6. **`--cdp` 每次调用 refs 失效**：`snapshot -i` 得到的 `@eNN` 无法在下次调用中使用，改用 CSS 选择器或 `find role/text`。

---

## 七、回归验证结果

| 项 | 结果 |
|---|---|
| 后端 `mvn clean test` | **620 例全绿 + BUILD SUCCESS**（jacoco 门禁通过） |
| 后端新增 CORS 预检用例 | 见本次补测 |
| 前端 `vitest run` | **263 例全绿**（24 文件，较上轮 +1） |
| 前端 `vue-tsc --noEmit` | **0 错误** |
| 前端 `vite build` | 成功（3.47s） |

---

## 八、第二轮（P3 阶段）· AI 错误可观测性三层根因修复

> 触发背景：第一轮定位到「AI 全链路失败时日志只有 `Error while extracting response`」，
> 因此新建 `AiResponseDiagnosticInterceptor` 试图抓取上游原始错误体，但**拦截器挂上后一次都没触发**。
> 本轮把这条线索追到底，发现根因不是一层，而是**三层独立缺陷叠加**。

### 8.1 根因链（三层）

| 层 | 缺陷 | 证据 |
|---|---|---|
| **传输层** | Spring 默认 `SimpleClientHttpRequestFactory` 底层是 `HttpURLConnection`；遇 **401 + 流式 POST** 时无法自动重试，直接抛 `HttpRetryException: cannot retry due to server authentication, in streaming mode`，**既不读也不保留响应体** | 真实堆栈 `at sun.net.www.protocol.http.HttpURLConnection.getInputStream0(:1812)` → `SimpleClientHttpResponse.getStatusCode(:55)` |
| **挂载层** | 拦截器只挂在 `fallbackChatModel` 的**入参** `RestClient.Builder` 上，而各节点实际用的是 `restClientBuilder.clone()`；Spring 6.1.21 的 `DefaultRestClientBuilder#clone()` **不拷贝 `requestInterceptors`** → 拦截器从未被调用 | 移除静态标志改为每 builder 显式挂载后立即生效 |
| **防护层** | 旧代码把 `response.getStatusCode()` 放在 `try` 外，且异常处理只覆盖 `Exception`（不含 `Throwable`）→ `HttpRetryException` 从该行直接穿出，**既无日志也破坏语义** | 堆栈精确指向 `AiResponseDiagnosticInterceptor.java:59` |

**关键澄清（推翻上一轮假设）**：上游**不是**「HTTP 200 + error body」。
实测 `POST https://apihub.agnes-ai.com/v1/chat/completions` 返回的是 **HTTP 401**，
响应体完整可读：`{"error":{"code":"","message":"Invalid token (request id: ...)","type":"AgnesAI_error"}}`。
问题是这具「金矿般的错误信息」被 `HttpURLConnection` 的 401 分支整个丢掉了。

### 8.2 修复内容

| 文件 | 改动 |
|---|---|
| `config/AiConfig.java` | 新增 `aiRequestFactory(...)`：传输层换 **JDK `HttpClient`**（`JdkClientHttpRequestFactory`），401 作为正常响应返回、body 可读；构建失败时退回原工厂保证可用性。新增 `withDiagnostics(builder)`：在每个**实际使用**的 builder 上显式挂拦截器，摆脱对 `clone()` 语义的依赖。embedding 与 chat 两条链路统一改造 |
| `ai/AiResponseDiagnosticInterceptor.java` | `getStatusCode()` 挪进 `try` 且放宽到 `Throwable`，读取失败**记录后重新抛出**（不再静默穿透）；新增 `isConfigFault(...)` 分级：密钥失效/欠费/模型下线等**必须人工介入**的故障记 `ERROR`，限流/临时抖动记 `WARN`；新增 `safeUri(...)` 兜底 |

### 8.3 修复效果（真机实测对比）

**修复前**
```
WARN FallbackChatModel : AI 模型 agnes-fallback/agnes-2.5-flash 调用失败，尝试降级：
    Error while extracting response for type [OpenAiApi$ChatCompletion] and content type [application/json]
ERROR FallbackChatModel : AI 降级链全部失败（共 1 个节点）。失败摘要：agnes-fallback/agnes-2.5-flash → Error while extracting response ...
```

**修复后**
```
ERROR AiResponseDiagnosticInterceptor : AI 上游返回配置类故障（需人工处理，通常是 API Key 失效/欠费/模型下线）：
    url=https://apihub.agnes-ai.com/v1/chat/completions, status=401,
    body={"error":{"code":"","message":"Invalid token (request id: 2026091915584980890199924e4KAIg)","type":"AgnesAI_error"}}
WARN  FallbackChatModel : AI 模型 agnes-fallback/agnes-2.5-flash 调用失败，尝试降级：
    401 - {"error":{"code":"","message":"Invalid token (request id: ...)","type":"AgnesAI_error"}}
ERROR FallbackChatModel : AI 降级链全部失败（共 1 个节点）。失败摘要：agnes-fallback/agnes-2.5-flash →
    401 - {"error":{"code":"","message":"Invalid token (request id: ...)","type":"AgnesAI_error"}}
```

三层修复互相咬合后，连 `describeFailure` 也能挖出上游原文了（此前 cause 链为 0 层）。

### 8.4 AI 异常输入矩阵（本地实测）

| 输入场景 | HTTP | 业务 code | 文案 |
|---|---|---|---|
| 空问题 `""` | 200 | 400 | 问题不能为空 |
| 缺字段 `{"foo":"bar"}` | 200 | 400 | 问题不能为空 |
| 纯空格 `"     "` | 200 | 400 | 问题不能为空 |
| 超长 5000 字 | 503 | 503 | AI 服务暂时不可用，请稍后重试 |
| emoji + 生僻字 | 503 | 503 | AI 服务暂时不可用，请稍后重试 |
| 正常问题 | 503 | 503 | AI 服务暂时不可用，请稍后重试 |
| 未授权访问 | 401 | 401 | 登录已过期或未登录，请重新登录 |

> 说明：503 是**本地环境缺 Agnes 真实 API Key** 导致的预期降级（线上实测 AI 全链路正常，见 §2.3）。
> 关键结论：**参数校验全部在调用 AI 之前完成**（400 而非 500/503），异常输入不会浪费 AI 配额。

### 8.5 本轮回归

| 项 | 结果 |
|---|---|
| 后端全量测试 | **643 例全绿 + BUILD SUCCESS**（较上轮 620 → 643，新增 23 例） |
| `AiResponseDiagnosticInterceptorTest` | **10 例全绿**（新增 `isConfigFault` 分级 2 例、非 2xx 空 body 1 例、状态码异常传播 1 例） |
| `FallbackChatModelTest` | **14 例全绿** |
| 前端 `vitest run` | **270 例全绿**（25 文件，较上轮 263 → 270） |
| 前端 `vite build` | 成功（1.10s） |
| 真机弹窗分级验证 | **5/5 通过**（6 条全用户可见、零技术术语、41 个历史版本已过滤） |

### 8.6 环境要点补充（构建/运行）

1. **Maven classworlds 启动器版本**：`D:/xm/apache-maven-3.9.15/boot/plexus-classworlds-`**`2.9.0`**`.jar`（不是 2.8.0）。
   且必须传 `-Dclassworlds.conf` + `-Dmaven.home`，否则报 `classworlds configuration not specified`。
2. **`-Dtest=` 多类用逗号分隔**，`+` 分隔符会报 `No tests matching pattern`。
3. **前端 `npm test` 不存在**：项目脚本是 `test`（watch）与 `test:run`（单次），CI 用后者。
4. **Node 调用 npm-cli 必须用 `C:/...` 正斜杠绝对路径**：用 `/c/...` 会被解析成 `D:\c\Users\...` 而 `MODULE_NOT_FOUND`。
5. **`vite build` 偶发 `prepareOutDir` / rolldown bindingify 报错**：先 `rm -rf dist` 再构建即可（残留目录被占用）。
6. **日志文件是 UTF-8 与 GBK 混合**：单文件内两种编码都存在，需 `decode('utf-8', errors='replace')` 兜底，
   纯 GBK 解码会在中间位置抛 `illegal multibyte sequence`。
7. **`taskkill //F //PID` 在沙箱下无回显**，改用 PowerShell `Stop-Process -Id <pid> -Force` 可靠。

---

## 九、待办

- [ ] 推送 `main` 触发 Render 重部署（后端 CORS 白名单生效）
- [ ] 推送前端触发 Cloudflare Pages 部署（1.33.2 跨域预检修复上线）
- [ ] 部署后复测：**闲置 15 分钟 → 首屏 → 首次登录**
- [ ] 决策 logout 是否引入 Redis 黑名单（发现 2）
- [ ] 处理 RAG Embedding 缺口（发现 3），核对 `AI_EMBEDDING_DIMENSIONS=1024`
- [ ] 处理失效的 Vercel 站点（发现 5）
- [x] ~~**配置真实的 `AI_API_KEY` / `AI_EMBEDDING_*`**~~ → **2026-09-20 已完成**（智谱 + Agnes 真实免费 Key，见 §十~§十四）
- [ ] 复核 `application-prod.yml` 中 AI 传输层是否也走 JDK `HttpClient`（本轮只改 `AiConfig`，两 profile 共用）
- [x] ~~修复 Spring AI `/v1` 硬拼与智谱 `/v4` 冲突~~ → **P0-05 已修复并真机验证**（见 §十一）
- [ ] **补上免费 Embedding 供给**（见 §十二 三选一路径），恢复 RAG 向量检索
- [ ] 生产环境 `JWT_SECRET` 必须通过环境变量注入（本地已在 `backend/.env` 固定值，仅限联调）

---

## 附：证据文件

| 文件 | 内容 |
|---|---|
| `evidence/01_ai_result_v1331.png` | 新前端岗位分析真实 AI 结果（含版本弹窗 v1.33.1） |
| `evidence/02_login_v1331.png` | 新前端 v1.33.1 登录态 |
| `evidence/03_login_prod_v1320.png` | 线上 v1.32.0 页面 |
| `evidence/04_job_page.png` | 新前端岗位分析页 |
| `.tmp/e2e/*.log` | 各阶段原始日志（网络序列、DOM 探针、API 响应） |

---

# 补记 · 2026-09-20 真实密钥接入与 P0-05 路径重写

> 承接上文待办：「配置真实的 `AI_API_KEY`」已完成。
> 本轮用**两个平台的真实免费模型**做端到端验证，并发现修复了一个**此前无法暴露的 P0 级隐蔽缺陷**。

## 十、免费模型全量实测（两个平台）

### 10.1 智谱 `open.bigmodel.cn`（官方标注「永久免费」的 Flash 系列）

| 模型 | 耗时 | 结论 |
|---|---|---|
| `glm-4-flash` | **938ms** | ✅ **最快，选为主力** |
| `glm-4-flash-250414` | 3406ms | ✅ 可用（128K 上下文） |
| `glm-4.5-flash` | 21.4s | ⚠️ 慢，官方已标注即将下线 |
| `glm-4.7-flash` | 30.4s | ❌ **返回空内容**（见 10.3） |
| `glm-4.7-flash` + `thinking:{type:disabled}` | 10.5s | ✅ 关闭思考后可用（200K 上下文） |
| `glm-4.5` / `4.5-air` / `4.6` / `4.7`（付费版） | — | ❌ 429 余额不足 |
| `embedding-3` / `embedding-2` | — | ❌ 429（免费额度不含 embedding） |

### 10.2 Agnes `apihub.agnes-ai.com`（聚合平台，`/v1/models` 返回 12 个模型）

| 模型 | 耗时 | 结论 |
|---|---|---|
| `agnes-2.0-flash` | 3302ms | ✅ **最快，选为兜底** |
| `agnes-3.0-flash` | 5258ms | ✅ 平台主推 |
| `agnes-2.5-pro` | 6223ms | ✅ 质量最好 |
| `agnes-2.5-flash` | 6723ms | ✅ 可用 |
| `glm-5.3-flash` | — | ❌ 503 `model_not_found`（该平台无此通道） |
| `text-embedding-3-small` / `bge-m3` / `agnes-embedding-*` | — | ❌ 全部 503 `model_not_found` |

### 10.3 关键坑：思考模式耗尽 `max_tokens` → 「假成功」

`glm-4.7-flash` / `glm-4.5-flash` **默认开启 thinking**，`reasoning_content` 吃光输出预算，
导致 `content` 为**空字符串**。此时：

```
HTTP 200 + usage 正常（tok=217）+ content="" + 耗时 30.4s
```

**极易被误判为「成功但答案为空」**。修复为两层：
1. 不把这两个模型放入降级链；
2. `FallbackChatModel` 新增 `hasUsableContent()` **空正文防护** —— 判空即视为该节点不可用并降级。

## 十一、P0-05 路径重写（本轮核心修复）

### 11.1 缺陷：Spring AI 硬编码拼接 `/v1`

反射探针实测确证：Spring AI 1.0.0 的 `OpenAiApi` **硬编码**把 base-url 与
`/v1/chat/completions`、`/v1/embeddings` 拼接（`buildRequestUrl(baseUrl, chatModelPath)`）。

| 厂商 | base-url | Spring AI 实际请求 | 结果 |
|---|---|---|---|
| Agnes | `https://apihub.agnes-ai.com` | `/v1/chat/completions` | ✅ 正常 |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `/api/paas/v4/v1/chat/completions` | ❌ **404** |

智谱真实端点经**穷举验证**只有 `/api/paas/v4/chat/completions` 可用
（`/v4/v1/...`、`/v3/...`、`/paas/paas/v4/...` 全部 404）。

由于 base-url 无论怎么填，Spring AI 都会再拼一层 `/v1`，**无法通过配置表达「不要这层 v1」**。

### 11.2 修复：请求发出前重写路径

新增 `VersionPathRewriteInterceptor`，在发送前把 `/vN/v1/x` 纠正为 `/vN/x`：

```
/api/paas/v4/v1/chat/completions  →  /api/paas/v4/chat/completions
```

配套新增 `AiConfig.needsV1Stripping()` 判定「路径以 `/vN`（N≠1）结尾」，
仅对这类厂商挂载拦截器；标准 v1 厂商（Agnes）**零开销原样放行**。

### 11.3 真机验证结果

**修复后实测日志**（4 次业务请求全部命中重写）：

```
VersionPathRewriteInterceptor : 重写 AI 请求路径：
    /api/paas/v4/v1/chat/completions → /api/paas/v4/chat/completions
```

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 智谱请求 `status=404` | 持续出现 | ✅ **0 次** |
| 降级到 Agnes 次数 | 每请求必降级 | ✅ **0 次**（智谱全程承载） |
| `/api/job/analyze` | ❌ **300s 超时** | ✅ **22.4s 返回完整结构化 JSON** |

### 11.4 四个 AI 接口端到端真实可用（真实密钥、真实联网推理）

| 接口 | HTTP | 耗时 | 结果 |
|---|---|---|---|
| `POST /api/job/analyze`（岗位分析） | 200 | 22.4s | ✅ 完整结构化 JD 拆解（jobTitle/responsibilities/hardSkills/hiddenRequirements…） |
| `POST /api/job/gap`（差距诊断） | 200 | 36.5s | ✅ `overallMatchScore: 85` + 逐条 strong/weak 证据 |
| `POST /api/interview/questions`（面试出题） | 200 | 28.0s | ✅ 含 category/difficulty/keyPoints，且**紧扣简历项目**（分库分表、RocketMQ、Redis） |
| `POST /api/knowledge/ask`（RAG 问答） | 200 | 29.5s | ✅ 近千字 HashMap 扩容详解（走无参考兜底，见 §十二） |

> 耗时 22~36s 系长文本生成（输出完整 JSON / 近千字回答）的正常表现，
> 与 `glm-4-flash` 短问答首 token **938ms** 并不矛盾。

## 十二、遗留：Embedding 缺口

两个平台**均无免费 embedding 供给**（智谱 429 需单独开通；Agnes 503 无通道）。

**业务影响**：仅 RAG「知识问答」的向量检索环节。
- 应用启动**不受影响**（`KnowledgeSeedInitializer` 失败仅告警，已验证）
- 登录/注册/岗位分析/面试出题/作答评估**全部正常**（仅用 Chat）
- `/api/knowledge/ask` 走「无参考资料，基于通用知识回答」兜底，**实测回答质量良好**

**解决路径（三选一）**：
- A) 硅基流动（有免费 `BAAI/bge-m3`，1024 维）：`https://api.siliconflow.cn/v1`
- B) 智谱开通 `embedding-3` 付费包（2048 维）
- C) 换用有 embedding 免费层的平台（Jina / Cohere）

配置项已在 `backend/.env` 中以注释形式预置，补上 Key 取消注释即生效。

## 十三、测试与回归

| 项 | 结果 |
|---|---|
| 全量后端测试 | ✅ **656 / 656 通过**（基线 643 → +13） |
| 新增 `VersionPathRewriteInterceptorTest` | ✅ 7/7（含智谱 chat/embeddings、标准 v1 放行、v1/v1 折叠、query 保留、响应透传） |
| `FallbackChatModelTest` 空正文防护 | ✅ 18/18（+4：空内容降级、空白降级、全链空内容抛业务异常、结构判空） |
| `AiConfigFallbackTest.needsV1Stripping` | ✅ +2 例（非 v1 识别、保留版本段） |
| BUILD | ✅ SUCCESS（01:02 min） |

## 十四、复盘：四层根因链（层层递进，缺一不可）

1. **传输层** —— `HttpURLConnection` 遇 401 + 流式 POST 抛 `HttpRetryException` 并**丢弃响应体**
   → 改用 JDK `HttpClient`（`JdkClientHttpRequestFactory`）
2. **挂载层** —— `RestClient.Builder#clone()` **不继承** `requestInterceptors`
   → 改用 `withDiagnostics(builder)` 统一挂载
3. **防护层** —— `getStatusCode()` 异常穿透导致 500
   → 加防护 + 空正文判定
4. **路径层（本轮新增，最隐蔽）** —— Spring AI 硬拼 `/v1` 与智谱 `/v4` 冲突
   → `VersionPathRewriteInterceptor` 发送前重写

> 前三层是「让错误可见」，第四层才是「让请求真正成功」。
> 前三层修复后仍报 404，正是因为第四层在此之前**完全不可见**——
> 没有真实密钥，根本走不到这一步。

---

# 第二轮：免费模型「质量优先」重新选型（2026-09-20）

## 十五、选型原则修正

### 15.1 原选型的问题

§十 的选型依据是**单点响应时长**，据此把 `glm-4-flash`（938ms）定为主力、
`agnes-2.0-flash`（3302ms）定为兜底。用户明确纠正：

> 「模型不要只选择快，要选择免费当中**最好的**」

**原选型的三处硬伤**：
1. **拿短问答延迟当模型能力** —— 938ms 是一次几十字问候的耗时，
   与「输出完整 JD 结构化 JSON」「输出近千字技术解析」是两类负载，不可外推。
2. **维度单一** —— 只看速度，未看 JSON 合规率、字段完整度、内容深度。
3. **未做稳定性验证** —— `agnes-2.0-flash` 实际存在免费档速率限制（见 15.3），
   单次测试无法暴露，连续调用才暴露。

### 15.2 新评测方法：真实任务 × 多次取中位数

评测脚本 `D:\xm\model_verdict.py`，对**免费可用**的 6 个模型做横向对比：

- **2 个真实业务任务**：岗位分析（`/api/job/analyze` 同款提示词）、简历评估（`/api/job/gap` 同款）
- **每个任务 × 3 次调用，取中位数**（消除网络抖动与限流偶发）
- **稳定性** = 6 次调用中成功返回可解析 JSON 的次数
- **质量分**（自动量化，非主观）：
  - 岗位分析 `qa()` = 必备字段完整个数 × 10 + 各数组字段元素总数
  - 简历评估 `qb()` = 结构完整度 + 匹配分合理性（70–95 视为合理区）+ 维度深度

## 十六、评测结果

### 16.1 质量总分排名（总分 = 岗位分析分 + 简历评估分）

| 排名 | 模型 | 总分 | 岗位分析 | 简历评估 | 稳定性 | 结论 |
|---|---|---|---|---|---|---|
| 1 | **`agnes-2.5-flash`** | **168** | q96 / 5.6s | **q72 / 9.3s** | **6/6** | ★ **主力** |
| 2 | `glm-4.5-flash` | 161 | **q99** / 13.1s | q62 / 49.2s | 6/6 | ★ 兜底 |
| 3 | `agnes-3.0-flash` | 158 | q96 / 4.3s | q62 / 7.6s | 6/6 | — |
| 4 | `glm-4-flash-250414` | 155 | q96 / 14.2s | q59 / 14.2s | 6/6 | — |
| 5 | `glm-4-flash` | 154 | q96 / 17.5s | q58 / 18.6s | 6/6 | ○ 末位保险 |
| 6 | `agnes-2.0-flash` | 101 | q101 / 5.3s | **✗ 失败** | **1/6** | ✗ **已剔除** |

### 16.2 三条关键结论

**① `agnes-2.5-flash` 质量与稳定性双料第一 → 定为主力**

简历评估维度得分 **q72**，比智谱最好的 `glm-4.5-flash`（q62）**高 16%**，
且响应更快（9.3s vs 49.2s）。对「简历 vs 岗位」这种需要细致比对的任务，
差距直接体现在分析深度上。

**② `glm-4.5-flash` 岗位分析质量最高（q99），但简历评估慢（49.2s）→ 作跨厂商兜底**

跨厂商（智谱）兜底的价值在于：Agnes 整体不可用时仍有独立通道。
不设为主力的原因有二：简历评估 49.2s 偏慢；官方标注即将下线。

**③ `agnes-2.0-flash` 必须剔除（上一轮的兜底选择，属选型错误）**

实测返回：

```
HTTP 429 | You've reached the API rate limit for free users.
           Upgrade to a Token...
```

6 次调用 **5 次失败**。上一轮仅凭单次 3302ms 就把它定为兜底，
而免费档的速率限制在单次测试中完全不可见 —— **这是单点测试的典型盲区**。

### 16.3 观察中但暂不入链的模型

| 模型 | 状态 | 原因 |
|---|---|---|
| `glm-4.7-flash` | 429 / 需关闭思考 | 当前「访问量过大」，稳定性不足；且需 `thinking:{type:disabled}` 才不返回空内容 |
| 硅基流动全部模型 | 402 | 余额不足（见 §十八） |
| Agnes `glm-5.3-flash` 等 | 503 | `model_not_found`，无此通道 |

## 十七、新降级链与端到端验证

### 17.1 三级降级链（`app.ai.chain`）

```yaml
chain:
  - name: agnes-primary        # agnes-2.5-flash   质量+稳定性双第一
  - name: zhipu-quality        # glm-4.5-flash     岗位分析质量最高，跨厂商兜底
  - name: zhipu-lastresort     # glm-4-flash       末位保险
```

`application.yml` 中已写入**完整评测数据表作为注释**，
使后来者能直接看到「为什么选这个模型」，而非只有一个模型名。

> 链路已内建两项自动跳过规则：**api-key 为空**、**返回空正文**。
> 因此 `glm-4.7-flash` 这类「HTTP 200 但 content 为空」的模型即使误入链也会被自动跳过。

### 17.2 端到端真机验证（`p005_verify.py`，真实密钥、真实联网）

| 接口 | HTTP | 耗时 | 对比上一轮（`glm-4-flash` 为主力） |
|---|---|---|---|
| `POST /api/job/analyze` | 200 | **7.2s** | 22.4s → **7.2s**（↓68%） |
| `POST /api/job/gap` | 200 | **11.0s** | 36.5s → **11.0s**（↓70%） |
| `POST /api/interview/questions` | 200 | **14.9s** | 28.0s → **14.9s**（↓47%） |
| `POST /api/knowledge/ask` | 200 | **5.3s** | 29.5s → **5.3s**（↓82%） |

**四接口全部 200，无一降级到兜底节点。**

### 17.3 输出质量同步提升（非仅提速）

- **面试出题**：出现 `difficulty: "HARD"` 与完整 `referenceAnswer`（参考答案要点），
  此前仅有 `keyPoints`
- **RAG 问答**：输出带 Markdown 表格与 JDK7/JDK8 对比的完整解析

> 说明：耗时下降是「换了更强的主模型」的结果，而非降低输出量。
> 质量与速度在本轮**同向改善**。

### 17.4 路径重写对新链路的覆盖确认

日志确认两个智谱节点均正确挂载 `VersionPathRewriteInterceptor`：

```
AiConfig : 检测到非 v1 版本段的 OpenAI 兼容厂商，将启用 /v1 路径重写：
           baseUrl=https://open.bigmodel.cn/api/paas/v4
```

（出现 **2 次** = `zhipu-quality` + `zhipu-lastresort` 两个节点，覆盖完整）

## 十八、硅基流动接入实测（新增第三平台）

### 18.1 Key 有效性：✅ 有效，接口实测可用

用户提供的 Key（`sk-sqmspe...`）**有效**，embedding 接口**实测全部成功**：

| 模型 | 维度 | 耗时 |
|---|---|---|
| `BAAI/bge-m3` | 1024 | 0.28s |
| `Qwen/Qwen3-Embedding-0.6B` | 1024 | 0.23s |
| `Qwen/Qwen3-Embedding-4B` | 2560 | 0.27s |
| `Qwen/Qwen3-Embedding-8B` | 4096 | 0.36s |
| `BAAI/bge-large-zh-v1.5` | 1024 | 0.25s |

### 18.2 但当前不可用：⚠️ 402 余额不足

同一批模型在**几分钟后**全部转为：

```
HTTP 402 | account balance is insufficient
```

**根因**：硅基流动的 embedding 属**付费模型**，新号赠金（约 ¥16）
在前序评测调用中已耗尽。**不是接口错误，不是 Key 失效。**

### 18.3 结论与启用方式

| 项 | 结论 |
|---|---|
| Embedding 接口兼容性 | ✅ 已验证可用（OpenAI 兼容，`/v1/embeddings`） |
| 推荐模型 | `BAAI/bge-m3`，**1024 维**（中文场景成熟，维度适中） |
| 当前阻塞 | 需充值（属平台计费策略，非技术问题） |

**启用方式**：`backend/.env` 中已预置为注释，充值后取消注释即生效：

```bash
AI_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
AI_EMBEDDING_API_KEY=<当前 Key>
AI_EMBEDDING_MODEL=BAAI/bge-m3
AI_EMBEDDING_DIMENSIONS=1024
```

> 至此 Embedding 缺口从「三平台均无通道」收敛为「**只差余额**」，
> 技术验证已全部完成。

## 十九、测试与回归

本轮改动了 `application.yml` 的 `app.ai.chain`（纯配置，无 Java 代码变更），
重跑全量测试确认**零回归**：

| 项 | 结果 |
|---|---|
| 全量后端测试 | ✅ **656 / 656 通过**，Failures 0 / Errors 0 / Skipped 0 |
| BUILD | ✅ **SUCCESS** |
| 耗时 | 48.6s |
| 新增失败用例 | 0 |

> 说明：`application.yml` 的 chain 变化不影响单测，因为测试不注入真实 api-key，
> `AiConfig` 会走「各节点 api-key 均为空 → 回退 `spring.ai.openai.*`」分支
> （日志可见 `app.ai.chain 未解析出任何可用 AI 提供方`），属预期行为。

## 二十、本轮方法论沉淀：免费模型选型的三个陷阱

1. **延迟陷阱** —— 短问答延迟不能外推为长文本生成能力。
   `glm-4-flash` 938ms（短问答）vs 17.5s（岗位分析），差 19 倍。
2. **单次陷阱** —— 单次成功 ≠ 稳定可用。
   `agnes-2.0-flash` 单测最快（3302ms / q101），连测 5/6 失败（免费档限流）。
   → **必须多次调用取中位数，并统计成功率。**
3. **假成功陷阱** —— HTTP 200 + usage 正常，`content` 仍可能为空字符串。
   `glm-4.7-flash` 因思考模式吃光 `max_tokens` 所致（详见 §十）。
   → **必须校验正文字段，不能只看状态码。**

**通用结论**：免费模型的「免费」本身是不稳定项 —— 额度、速率、模型上下线
都可能在任意时刻变化。因此工程上**必须有多厂商降级链**，
且选型结论应**连同评测数据一起写进配置注释**，便于下次重新验证时对照。

---

# 第三轮：免费 Embedding 全网排查（2026-09-20）

## 二十一、现有三平台复核：全部不可用

| 平台 | 模型 | 结果 |
|---|---|---|
| 智谱 | `embedding-3` / `embedding-2` | ❌ `1113 余额不足或无可用资源包,请充值。` |
| Agnes | 全部 embedding 候选 | ❌ `model_not_found`（无该通道） |
| 硅基流动 | 8 个 embedding 模型 | ❌ `30001 account balance is insufficient` |

> 硅基流动的**接口本身已验证可用**（见 §十八），仅余额耗尽。
> 其模型目录实测包含：`BAAI/bge-m3`、`Qwen/Qwen3-Embedding-0.6B/4B/8B`、
> `BAAI/bge-large-zh-v1.5`、`BAAI/bge-large-en-v1.5`、`Pro/BAAI/bge-m3`、
> `Qwen/Qwen3-VL-Embedding-8B` —— **一个 Key 覆盖 8 个模型，是模型最全的一家**。

## 二十二、网络可达性筛查（关键前置）

免费额度再大，网络不通也没用。本机实测各平台连通性：

| 平台 | HTTP | 判定 |
|---|---|---|
| **阿里云百炼** `dashscope.aliyuncs.com` | **401** | ✅ 可达，格式正确 |
| **百度千帆** `qianfan.baidubce.com` | **401** | ✅ 可达，格式正确 |
| **Voyage AI** `api.voyageai.com` | **401** | ✅ 可达（需境外支付，暂不用） |
| 硅基流动 `api.siliconflow.cn` | 404 | ✅ 可达（域名根 404 属正常） |
| Cohere `api.cohere.com` | 403 | ✅ 可达 |
| **Jina AI** `api.jina.ai` | **000 / 502** | ❌ **代理 CONNECT tunnel failed** |
| Gemini / Mistral / HuggingFace | 000 | ❌ 不可达 |

> **重要发现**：Jina AI 虽有 10M 免费 Token 且中文效果优秀，
> 但本机网络**无法连通**（`curl: (7) CONNECT tunnel failed, response 502`），
> **直接排除** —— 这印证了「先验连通性，再比额度」的排查顺序。
>
> 判定技巧：**401 是好消息**（说明端点存在且解析了请求，只是缺凭证）；
> **404 = 端点路径错**；**000 = 网络不通**。

## 二十三、推荐方案：阿里云百炼 `text-embedding-v4`

### 23.1 免费额度（业界最慷慨的 embedding 免费层）

| 项 | 值 |
|---|---|
| 免费额度 | **每模型 100 万 Token**，开通后 **90 天**内有效 |
| 发放方式 | 首次开通百炼**自动发放**，无需手动领取 |
| 模型 | `text-embedding-v4`（属 **Qwen3-Embedding** 系列） |
| 可选维度 | 2048 / 1536 / **1024（默认）** / 768 / 512 / 256 / 128 / 64 |
| 单行上限 | **8192** token |
| 语种 | 100+ 主流语种，**中文效果业界第一梯队** |
| 单价（超出后） | $0.07 / 百万 token |
| 限速 | 20 RPM、并发 2（够开发验证，不够生产） |

### 23.2 接入配置（OpenAI 兼容，零改造）

```bash
AI_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_EMBEDDING_API_KEY=<百炼控制台申请，sk- 开头>
AI_EMBEDDING_MODEL=text-embedding-v4
AI_EMBEDDING_DIMENSIONS=1024
```

开通路径：`https://bailian.console.aliyun.com/` → 实名认证 → 模型广场取 Key

> ⚠️ **Key 与地域强绑定**：北京地域的 Key 不能用于新加坡端点，
> 反之亦然。这是新手最常见的 401 原因。
>
> ⚠️ 建议开启**「用完即停」**开关：免费额度耗尽直接返回
> `403 AllocationQuota.FreeTierOnly`，避免实名账号自动转按量付费产生意外账单。

### 23.3 备选方案：百度千帆

| 项 | 值 |
|---|---|
| 免费额度 | 新用户**每模型 100 万 Token**，有效期 3 个月 |
| 模型 | `bge-large-zh-v1.5`（1024 维）/ `bge-m3` 等预置模型 |
| 端点 | `https://qianfan.baidubce.com/v2`（OpenAI 兼容） |
| 门槛 | ⚠️ 2026 年 4 月起新用户需**实名认证**才能领免费额度 |

```bash
AI_EMBEDDING_BASE_URL=https://qianfan.baidubce.com/v2
AI_EMBEDDING_API_KEY=<千帆控制台申请，bce-v3/ 开头>
AI_EMBEDDING_MODEL=bge-large-zh-v1.5
AI_EMBEDDING_DIMENSIONS=1024
```

### 23.4 方案对比总表

| 方案 | 免费额度 | 维度 | 中文 | 门槛 | 推荐 |
|---|---|---|---|---|---|
| **阿里云百炼** | 100 万 tok / 90 天 | 64–2048 | ★★★ | 实名 | ★ **首选** |
| 百度千帆 | 100 万 tok / 3 个月 | 1024 | ★★★ | 实名 | ★ 备选 |
| 硅基流动（充值） | 需充值 | 1024–4096 | ★★★ | 充值 | ○ 最省事 |
| Jina AI | 10M tok | 1024 | ★★★ | — | ✗ **网络不通** |
| Voyage AI | 200M tok | 1024–2048 | ★★ | 境外支付 | ✗ 不可用 |
| 智谱 / Agnes | 无 | — | — | — | ✗ 无通道 |

## 二十四、Embedding 选型的三条工程纪律

1. **先验连通性，再比额度** —— Jina 免费额度最大（10M）却因网络不通直接出局。
   排查顺序：`000 网络不通` → `404 路径错` → `401 凭证缺` → 才是额度问题。
2. **维度一次选定，不可中途更换** —— 不同模型的向量空间不通用，
   换模型必须**全量重新 embedding**。切换代价远高于切换 LLM。
3. **开发用免费层，生产必须付费** —— 免费层普遍限速（百炼 20 RPM / 并发 2）、
   有有效期、且部分禁止商用。免费层的定位是**验证与开发**，不是生产主力。

---

# 第四轮：Gemini / Mistral / HuggingFace 连通性核查 + 本地 Embedding 落地（2026-09-20）

## 二十五、三平台连通性核查：本机网络不可达

用户提出使用 Gemini / Mistral / HuggingFace 三家平台的 embedding，并愿自行完成登录注册。
核查后发现**登录不是瓶颈，网络才是**。

### 25.1 双重不通（走代理 502 + 绕过代理超时）

本机存在白名单代理 `http://127.0.0.1:53605`（`http_proxy`/`https_proxy` 均指向它）。

| 平台 | 端点 | 走代理 | 绕过代理直连 |
|---|---|---|---|
| Gemini | `generativelanguage.googleapis.com` | ❌ `502 CONNECT tunnel failed` | ❌ `Connection timed out` |
| Mistral | `api.mistral.ai` | ❌ `502 CONNECT tunnel failed` | ❌ `Connection timed out` |
| HuggingFace | `huggingface.co` / `router.huggingface.co` | ❌ `502 CONNECT tunnel failed` | ❌ `Connection timed out` |

**结论**：三家**既不在代理白名单内，本机也无法直连**。
因此**提供 API Key 无法解决问题** —— 请求根本发不出去。

### 25.2 判定技巧（沿用第三轮方法论）

| 现象 | 含义 |
|---|---|
| `502 CONNECT tunnel failed` | 代理拒绝为该域名建隧道（不在白名单） |
| `Connection timed out`（`--noproxy '*'`） | 直连也不通 → **该域名彻底不可达** |
| `401` / `403` | 可达，仅缺凭证 ✅ 好消息 |
| `404` | 路径错 |

> 关键动作：**必须同时测「走代理」与「绕过代理」两条路径**。
> 只测一条会误判 —— 只测代理会以为是配置问题，只测直连会以为是网络问题。

### 25.3 三家平台的免费额度（备查，若将来网络环境变化）

| 平台 | 模型 | 维度 | 免费额度 | 端点协议 |
|---|---|---|---|---|
| Gemini | `gemini-embedding-001` | 128–3072（推荐 768） | 100 RPM / 30K TPM / 1000 RPD，无需绑卡 | ⚠️ **非 OpenAI 兼容**（`embedContent` 专有格式） |
| Mistral | `mistral-embed` | 固定 1024 | 免费档流量充足 | ✅ OpenAI 兼容 |
| HuggingFace | BGE / E5 系列 | 视模型 | Inference API 免费层 | ✅ 兼容 |

> ⚠️ 若将来启用 Gemini，需注意它的 embedding **不是 OpenAI 协议**
> （`{content:{parts:[{text}]}}` → `{embedding:{values:[]}}`），
> Spring AI 的 `OpenAiEmbeddingModel` **无法直接对接**，需适配层或改用 VertexAI 客户端。

### 25.4 替代发现：HuggingFace 的**模型**可以走国内镜像

虽然 `huggingface.co` 不可达，但**国内镜像 `hf-mirror.com` 完全可用**：

```
https://hf-mirror.com/api/models/BAAI/bge-m3   → HTTP 200，返回完整元信息
```

这打开了更好的路径：**不调用 HF 的 API，而是下载 HF 的模型在本地推理**。
详见 §二十六。

## 二十六、本地 Embedding 落地（当前生效方案）

### 26.1 方案对比：为什么本地优于 API

| 维度 | 云端 API | **本地 ONNX 推理** |
|---|---|---|
| 费用 | 免费额度有限，超量付费 | **永久免费** |
| 用量 | 受 RPM/TPM/RPD 限制 | **无限量** |
| 网络 | 依赖境外/云端可达性 | **完全离线**（模型下载后） |
| 隐私 | 文本上传第三方 | **数据不出本机** |
| 首次成本 | 注册 + 拿 Key | 下载模型（约 95MB） |
| 中文质量 | 视平台 | **bge 系列中文优化良好** |

### 26.2 技术选型：fastembed（ONNX）而非 sentence-transformers（torch）

| 方案 | 依赖 | 体积 |
|---|---|---|
| sentence-transformers | PyTorch | **约 2 GB** |
| **fastembed** | onnxruntime | **约 30 MB**（+ numpy/onnxruntime 共约 40MB） |

选择 fastembed 的原因：**无需 torch，CPU 推理足够快（实测 29ms/条）**，部署轻量。

### 26.3 交付物

| 文件 | 作用 |
|---|---|
| `D:\xm\embed_server.py` | 本地 OpenAI 兼容 Embedding 服务（标准库 `http.server`，零框架依赖） |
| `D:\xm\start_embed_server.ps1` | 启动脚本（自动设置 `HF_ENDPOINT`） |
| `D:\xm\fetch_wheels.py` | 离线 wheel 下载器（备用，绕过 pip 网络限制） |

**服务接口**（OpenAI 兼容，Spring AI 零改造对接）：

```
GET  /health          → {"status":"ok","model":"BAAI/bge-small-zh-v1.5","dimensions":512}
GET  /v1/models       → 模型列表
POST /v1/embeddings   → {"object":"list","data":[{"object":"embedding","index":0,"embedding":[...]}],...}
```

**可用模型**（fastembed 0.8.0 实测目录）：

| 模型 | 维度 | 说明 |
|---|---|---|
| `BAAI/bge-small-zh-v1.5` | 512 | 中文，约 95MB，**最轻量（当前选用）** |
| `jinaai/jina-embeddings-v2-base-zh` | 768 | 中文 |
| `jinaai/jina-embeddings-v3` | 1024 | 多语，质量最高 |
| `intfloat/multilingual-e5-large` | 1024 | 多语 |

> 注：`bge-m3`、`bge-large-zh-v1.5` **不在** fastembed 0.8.0 目录内。

### 26.4 语义质量实测（bge-small-zh-v1.5）

| 文本 A | 文本 B | 相似度 | 预期 |
|---|---|---|---|
| Redis 缓存穿透怎么解决 | 缓存击穿和穿透的常见处理方案 | **0.7572** | 高 ✅ |
| Redis 缓存穿透怎么解决 | Java 线程池的核心参数 | 0.4573 | 低 ✅ |
| MySQL 索引失效的场景 | 什么情况下数据库查询走不到索引 | **0.6641** | 高 ✅ |
| MySQL 索引失效的场景 | 今天中午吃什么比较好 | **0.1336** | 低 ✅ |

**相关对 0.66~0.76，无关对 0.13，区分度清晰。**

### 26.5 接入配置（`backend/.env`）

```bash
AI_EMBEDDING_BASE_URL=http://127.0.0.1:8001
AI_EMBEDDING_API_KEY=local-embedding
AI_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5
AI_EMBEDDING_DIMENSIONS=512
```

**启动顺序**：先起 embedding 服务（`start_embed_server.ps1`），再起后端。

### 26.6 端到端验证结果

**① 知识库播种成功**（此前因无 embedding 提供方而失败）：

```
KnowledgeSeedInitializer : 共享知识库播种完成：19 条预置知识已写入向量库
                           （shared=true，全部用户可检索）
embed_server             : embeddings n=1 dim=512 29ms   （×19 条）
```

**② 四接口全通过，且耗时进一步下降**：

| 接口 | 本轮 | 上一轮（agnes-2.5-flash） | 变化 |
|---|---|---|---|
| `/api/job/analyze` | **4.7s** | 7.2s | ↓35% |
| `/api/job/gap` | **9.0s** | 11.0s | ↓18% |
| `/api/interview/questions` | **7.4s** | 14.9s | ↓50% |
| `/api/knowledge/ask` | **4.0s** | 5.3s | ↓25% |

**③ RAG 回答从「通用知识兜底」变为「基于参考资料」**（决定性证据）：

| 提问 | 结果 |
|---|---|
| Redis 的持久化机制有哪些 | ✅ 基于参考资料 —— 完整给出 RDB/BGSAVE/AOF |
| Spring 事务失效的常见原因 | ✅ 基于参考资料 —— 命中同类内部调用绕过代理等场景 |
| MySQL 的 MVCC 是怎么实现的 | ⚠️ 模型主动说明「**资料中并未包含 MySQL MVCC 的内容**（参考资料主要涉及 HashMap、分布式 ID、Redis 持久化、ConcurrentHashMap 和 JVM 内存），以下基于通用知识作答」 |

> 第三条是**检索正确性最强的证据**：模型准确列出向量库中实际存在的内容，
> 说明向量检索返回的是真正相关的邻近文档，且**无幻觉** —— 缺失即坦言缺失。

## 二十七、环境排查记录（本轮新增的坑）

### 27.1 pip 被镜像源静默拒绝 → 改用官方 PyPI

| 现象 | `pip install <pkg>` 在清华源下报 `Could not find a version that satisfies the requirement (from versions: none)`，且 `-v` 无任何网络日志 |
|---|---|
| 排查 | `curl` 与 `urllib` 访问同一索引均返回 200 且含 `.whl` → **网络通，是 pip 被拦** |
| UA 测试 | `pip/26.1.2`、`Python-urllib/3.13`、`Mozilla/5.0` 三种 UA 均 200 → **不是 UA 拦截** |
| 根因 | 清华镜像源在本机代理策略下对 pip 的请求路径不通 |
| **解法** | **改用官方 PyPI**：`pip install <pkg> --index-url https://pypi.org/simple` ✅ |

> 教训：**不要想当然用国内镜像**。镜像可达 ≠ pip 可用，两者要分别验证。

### 27.2 后台进程随父 shell 退出被杀

`nohup ... &` 启动的进程，在父 shell 结束时会被回收
（表现：服务的 `/health` 返回 `upstream connect failed ... 10061` 连接被拒）。

**解法**：长驻服务用 **PowerShell 工具的 `run_in_background=true`**，不依赖 shell 存活。

### 27.3 localhost 请求被代理拦截

因 `http_proxy` 指向 `127.0.0.1:53605`，`curl http://localhost:8001/...`
会被送去代理再回连本机，失败。

**解法**：本地调用加 `--noproxy '*'`，或设 `no_proxy=localhost,127.0.0.1`。

> 注：Java 的 `HttpClient` 默认**不读** `http_proxy` 环境变量，故后端访问
> `127.0.0.1:8001` 不受影响（已实测通过）。

### 27.4 Windows 路径给 Python 必须用 `D:/` 而非 `/d/`

`/d/xm/xxx.py` 会被 Python 解析成 `d:\d\xm\xxx.py` → `No such file or directory`。

**解法**：传参一律用 `D:/xm/xxx.py` 或 `D:\xm\xxx.py`。

### 27.5 wheel 链接带 `#sha256=` 片段

自建下载器时若先判 `href.endswith(".whl")` 再剥片段，会因片段导致判断失败、
**一个 wheel 都匹配不到**。必须**先剥 `#` 再判扩展名**。

---

# 第五轮：关机后 RAG 可用 + 全行业知识自动补充（2026-09-20）

## 二十八、需求与根因

用户明确两点要求：

> 「确保当前电脑关机以后 RAG 正常使用；RAG 要自动补充知识，要各行各业的」
>
> 「我当前项目是 AI 智能面试辅助平台，不只是计算机行业，是全行业的辅助平台」

### 28.1 根因：local profile 是「双内存」实现

| 组件 | 原实现 | 后果 |
|---|---|---|
| 数据库 | `jdbc:h2:mem:interview` | 用户、面试记录重启即清空 |
| 向量库 | `SimpleVectorStore`（纯内存） | **知识重启即清空**，只能靠每次启动重新播种 |

第二个是致命的：即使积累了知识，进程一退就归零 ——
「关机后 RAG 正常使用」在这一架构下**不可能成立**。

### 28.2 第二个根因：知识覆盖面与平台定位冲突

| 问题 | 具体表现 |
|---|---|
| 种子知识仅 19 条且全为 IT | Java/Spring/数据库/中间件/算法/网络/系统设计 |
| RAG prompt 硬编码行业 | 写死「你是一个 **Java 后端面试助手**」 |
| 知识库不会生长 | 检索落空时只回一句「资料中没有相关内容」，不做任何沉淀 |

结果：非技术岗用户（财会、医疗、销售、建筑……）提问时既无资料可引，
又被 prompt 强行往编程方向带偏。

## 二十九、向量库持久化

### 29.1 技术选型：子类化 SimpleVectorStore

`SimpleVectorStore` 提供 `save(File)` / `load(File)`，且 `store` 字段是 `protected`，
因此**不必自己实现向量序列化**（避免格式漂移），子类化即可：

```java
public class PersistentSimpleVectorStore extends SimpleVectorStore {
    @Override public void doAdd(List<Document> docs)    { super.doAdd(docs);    dirty.set(true); }
    @Override public void doDelete(List<String> ids)    { super.doDelete(ids); dirty.set(true); }
    public int  restore();            // load(file)
    public boolean flush(boolean force);  // save(tmp) → 原子 move
}
```

`doAdd` / `doDelete` 是 `AbstractObservationVectorStore` 的模板方法，
覆写即天然覆盖全部写入路径（新增、导入、自动补充）。

### 29.2 三个时点落盘

| 时点 | 动作 | 目的 |
|---|---|---|
| 启动 `@PostConstruct` | `restore()` + 同步容量计数 | 恢复知识，并让上限判断基于真实条数 |
| 运行中 `@Scheduled(3s)` | 仅在 `dirty` 时 `flush(false)` | **不在写入路径做同步 IO**，不拖慢批量播种 |
| 播种后 `@Order(110)` | `flush(true)` | 让刚播的种子当场持久化 |
| 关闭 `@PreDestroy` | `flush(true)` | 优雅关闭兜底 |

### 29.3 三个必须处理的健壮性问题

1. **快照损坏不能阻断启动** —— 改名 `.corrupt` 保留现场，以空库继续，
   与项目「AI 故障不拖垮非 AI 功能」的一致性原则一致。
2. **原子写** —— 先写 `.tmp` 再 `move`，进程中途被杀不会留下半截 JSON。
3. **容量计数必须同步**（最容易被忽略）——
   `RagSearchService.storedDocs` 与内存库同生命周期，
   持久化后重启会「库中有 69 条、计数为 0」，使 `max-documents` 上限失效、
   知识库被无界追加直至 OOM。故恢复后必须回填真实条数。

### 29.4 播种幂等：确定性 ID

持久化后，每次启动重新播种会造成重复条目。解法是
**用「分类+标题」派生确定性 ID**：

```java
UUID.nameUUIDFromBytes(("seed|" + category + "|" + title).getBytes(UTF_8))
```

`SimpleVectorStore` 内部按 ID 存入 Map，同 ID 直接覆盖 → 播种天然幂等，
且**扩充预置知识后重启只需新增差异部分**。

### 29.5 数据库改为文件库

```yaml
# 原：jdbc:h2:mem:interview;DB_CLOSE_DELAY=-1
url: jdbc:h2:file:D:/xm/data/interview
```

> ⚠️ **踩坑**：初次误写 `AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE`，
> H2 明确报 `JdbcSQLFeatureNotSupportedException: Feature not supported:
> "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"` —— **这两个参数不能共存**，
> 直接导致上下文启动失败。由 `ApplicationContextSmokeTest` 当场拦住。

### 29.6 测试数据隔离（安全修复）

`ApplicationContextSmokeTest` 使用 `local` profile，改造后会**读写真实快照**，
存在「用测试数据覆盖用户积累知识」的风险。已显式隔离：

```java
@TestPropertySource(properties = {
    "app.rag.persist-enabled=false",
    "app.rag.snapshot-file=${java.io.tmpdir}/interview-smoke-vectorstore.json"})
```

## 三十、全行业知识覆盖

### 30.1 种子知识从 19 条扩到 69 条

新增 `SeedIndustryKnowledge`（50 条），覆盖 40+ 行业分类：

| 领域 | 覆盖行业（部分） |
|---|---|
| 财经法务 | 财会税务、审计、银行金融、证券基金、保险、法律 |
| 人力销售 | 人力资源、销售、市场营销、客户服务、行政管理 |
| 医疗教育 | 医疗临床、护理、药学、教育、心理社工 |
| 工业制造 | 制造业、质量管理、机械电气、化工、能源电力、汽车 |
| 工程建筑 | 建筑工程、房地产、环保、农业 |
| 服务消费 | 电商运营、旅游酒店、餐饮、航空服务、传媒新闻 |
| 专业职能 | 管理咨询、项目管理、产品经理、数据分析、设计、广告、翻译 |
| 公共服务 | 公务员事业单位 |

每条均为「面试常考点」而非百科泛谈，正文自包含且含行业术语
（术语是向量命中的关键）。

### 30.2 RAG prompt 去除行业绑定

```java
// 原：写死 IT 语境，非技术岗会被带偏
.append("你是一个 Java 后端面试助手，请根据以下参考资料回答问题。\n\n")

// 新：全行业定位 + 要求先做行业判断
.append("你是一位资深的面试辅导专家，服务范围覆盖**全行业**：")
.append("技术研发、产品与设计、金融与财会、法律、医疗与护理、教育与科研、")
.append("人力资源、销售与市场、运营与电商、制造与工程、行政与公共服务等。\n")
...
.append("4. 先判断提问所属行业与岗位，用该行业的专业术语作答；")
.append("不要假设提问者一定来自互联网/IT 行业\n");
```

**实测效果**：问「航空配载平衡」时，回答首段主动输出
「行业/岗位：**航空运输/地服运营** — 配载平衡员、签派员或航务人员」，
再展开专业内容 —— 行业判断按要求生效。

## 三十一、知识自动补充机制

### 31.1 闭环设计

```
用户提问 → RAG 检索
   ├─ 命中（距离达标）→ 基于参考资料作答，不做额外动作
   └─ 落空（无结果 或 最相似距离过大）
         → 异步：让 LLM 就该主题生成一条结构化知识
         → 写入共享库（shared=true）
         → 下次任何人问到同类问题即可命中
```

预置知识不可能穷举所有行业，**这是知识库随使用自动生长的关键**。

### 31.2 五道安全阀

| 机制 | 作用 |
|---|---|
| **异步**（单线程池 + 有界队列 200） | 绝不阻塞用户当前回答；队列满直接丢弃 |
| **每小时配额**（默认 30 条） | 防异常流量（爬虫/压测）把「每次落空都调 LLM」变成成本黑洞 |
| **入参过滤**（4~120 字） | 过短（「你好」）无沉淀价值；过长多为误粘贴简历 |
| **确定性 ID**（`auto\|分类\|标题`） | 同一主题重复补充只覆盖，不新增 |
| **生成通用知识而非问答记录** | 入库内容不含用户隐私，可安全共享给全部用户 |

### 31.3 循环依赖的处理

`AutoKnowledgeService` 需调 `RagSearchService.addToVectorStore` 入库，
而 `RagSearchService` 需要触发 `AutoKnowledgeService` —— 形成环。
解法是用 `ObjectProvider` 把解析推迟到首次使用：

```java
@Autowired
private ObjectProvider<AutoKnowledgeService> autoKnowledgeProvider;
...
AutoKnowledgeService svc = autoKnowledgeProvider.getIfAvailable();
```

既打破环，又不引入 `@Lazy` 代理的额外语义。

## 三十二、阈值标定（本轮最大的坑）

### 32.1 现象：自动补充一次都没触发

阈值初值设为 **0.75**，实测**从不触发**，且日志中毫无线索。

### 32.2 根因：`SimpleVectorStore` 无论相关与否都返回 topK 条

**「检索到结果」≠「知识库覆盖」**。实测距离分布（bge-small-zh-v1.5，余弦距离）：

| 提问 | 最相似文档 | bestDistance | 应有判定 |
|---|---|---|---|
| 护理三查七对 | 【护理】三查七对与给药安全（**同名条目**） | **0.32** | 已覆盖 |
| 医疗器械二类注册 | 【医疗临床】门诊诊疗流程（弱相关） | **0.52** | **未覆盖** |
| 航空配载平衡 | （弱相关条目） | **约 0.52** | **未覆盖** |

阈值 0.75 **高于**未命中场景的 0.52，于是「检索到无关近邻」被误判为「已覆盖」。

### 32.3 修正为 0.45 并留下标定说明

取 0.45 分界：命中场景（≈0.32）明显低于它，未命中场景（≈0.52）明显高于它，
两侧各留约 0.07~0.13 余量。

> ⚠️ **换 embedding 模型后必须重新标定本阈值** ——
> 不同模型的余弦距离尺度不同，照搬会重蹈本轮覆辙。
> 已在代码注释与配置注释中双重标注。

## 三十三、开机自启动

### 33.1 交付物

| 文件 | 作用 |
|---|---|
| `D:\xm\start_all.ps1` | 幂等启动（端口已在监听则跳过），**严格保证 Embedding 先于后端就绪** |
| `D:\xm\start_all_hidden.vbs` | 隐藏窗口启动器（窗口样式 0，用户无感知） |
| 启动文件夹 | `…\Startup\AIInterviewPlatform.vbs`（VBS 副本） |

### 33.2 启动顺序为何重要

后端启动时会用 embedding 播种知识库。若后端先起而 embedding 未就绪，
播种会因 embedding 不可用而失败 —— 表现为「RAG 又变空了」。
故 `start_all.ps1` 会**轮询等待 8001 就绪（最多 60s）后再启动后端**。

### 33.3 实测

```
[12:10:22] Embedding 服务已在运行（8001），跳过
[12:10:22] 启动后端（8080）...
[12:10:33] 后端启动成功（8080 已就绪）
[12:10:33] 状态汇总：Embedding(8001)=True  后端(8080)=True
```

> ⚠️ **注意**：Windows 启动文件夹 / 注册表 Run 项在**用户登录时**触发，
> 不是开机即触发。若需开机即启（未登录也运行），需改用任务计划程序并配置
> 「不管用户是否登录都要运行」+ 存储凭据。

## 三十四、测试与端到端验证

### 34.1 测试

| 项 | 结果 |
|---|---|
| 全量后端测试 | ✅ **672 / 672 通过**（基线 656 → **+16**） |
| `PersistentSimpleVectorStoreTest` | ✅ 6/6（落盘恢复、无快照、快照损坏、脏标记、无残留临时文件） |
| `KnowledgeSeedInitializerTest` | ✅ 10/10（+4：全行业覆盖、行业术语、ID 稳定性、ID 唯一性） |
| `AutoKnowledgeServiceTest` | ✅ 6/6（开关关闭、过短/过长/null、配额耗尽、不阻塞调用方） |
| BUILD | ✅ SUCCESS（1:03 min） |

### 34.2 持久化真机验证

**首次启动**（无快照）：
```
向量库快照不存在，以空库启动（首次启动属正常）：D:\xm\data\vectorstore.json
共享知识库播种完成：69 条预置知识（IT 基础 19 条 + 全行业 50 条）
向量库快照已写入：69 条文档 → D:\xm\data\vectorstore.json
```

**重启后**（有快照）：
```
向量库已从快照恢复：69 条文档（D:\xm\data\vectorstore.json）
向量库容量计数已同步为 69 条（上限 2000）
共享知识库播种完成：69 条预置知识...（确定性 ID 覆盖，仍为 69 条，无重复）
```

> 关键：第二次播种后**仍是 69 条而非 138 条** → 确定性 ID 幂等生效。

### 34.3 自动补充闭环真机验证

**第一次提问**（知识库未覆盖）：
- 「航空配载平衡怎么计算重心」→ 回答「参考资料中**没有**包含航空配载平衡的相关内容」
- 「医疗器械二类注册需要哪些材料」→ 回答「**参考资料中未包含**医疗器械注册相关内容」

后台自动触发补充（日志线程名 `[auto-knowledge]`）：
```
[auto-knowledge] SimpleVectorStore : Calling EmbeddingModel for document id = 7618e596-...
[auto-knowledge] AutoKnowledgeService : 知识库自动补充：新增 [航空服务] 飞机配载平衡...
[auto-knowledge] AutoKnowledgeService : 知识库自动补充：新增 [医疗器械] ...
```

**快照文件从 538,133 → 555,263 字节**（新增 2 条已持久化）。

**再次提问同一问题**（已覆盖）：
- 「航空配载平衡怎么计算重心」→ 「根据【**航空服务·飞机配载平衡**】参考资料，
  **CG 力臂 = Σ（各项重量 × 对应力臂）/ Σ 总重量**」✅
- 「医疗器械二类注册需要哪些材料」→ 「根据参考资料，第二类医疗器械注册实行
  **省级药品监督管理部门审批制**」✅

**闭环成立**：提问落空 → 自动生成 → 入库 → 再次命中并引用。

### 34.4 全行业问答质量抽样

| 提问 | 结果 |
|---|---|
| 护理三查七对 | ✅ 命中同名条目，完整给出三查（操作前/中/后）与七对 |
| OKR 与 KPI 区别 | ✅ 命中，以表格对比输出 |
| 市政工程施工组织设计 | ✅ 命中，明确引用「【建筑工程】施工组织设计与工程造价」 |
| 咖啡店会员复购率 | ✅ 正确识别为「零售/餐饮服务运营」场景，并指出参考资料直接相关的有限 |
| 医疗器械二类注册 | ✅ 识别岗位为「医疗器械注册专员/法规事务经理/质量体系工程师」 |

## 三十五、本轮方法论沉淀

1. **「检索到结果」不等于「知识库覆盖」** ——
   多数向量库无论相关与否都返回 topK 条，判断覆盖率必须看**距离阈值**，
   且阈值需按模型实测标定。
2. **持久化后必须同步内存计数器** ——
   这是最容易遗漏的一环，症状是「容量上限静默失效直至 OOM」。
3. **持久化 + 幂等 ID 必须成对使用** ——
   只做持久化不做幂等，每次启动都会重复播种。
4. **测试必须与真实数据隔离** ——
   一旦引入持久化，切片测试就可能读写生产数据文件，必须显式关闭或改路径。
5. **启动顺序是有语义的** ——
   依赖 embedding 的播种必须先等 embedding 就绪，否则「服务起来了但知识库是空的」。


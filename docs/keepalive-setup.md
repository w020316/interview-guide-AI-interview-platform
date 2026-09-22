# 后端保活配置（解决「登录长时间进不去」）

> 结论先行：**Render 免费层冷启动实测中位约 258s，最慢超过 480s**（分布见第一节），
> 而此前前端只等 150s，所以「点登录 → 干等 → 失败」是必然结果。前端容错已放宽到 8 分钟
> （见 `frontend/src/utils/backendWake.ts`），但**真正的解法是别让它休眠**。
>
> ✅ **保活已于 2026-09-22 部署并验证**（Cloudflare Worker 方案）：
>
> | 项 | 值 |
> |---|---|
> | Worker | `render-keepalive` → https://render-keepalive.1181264839.workers.dev |
> | 定时 | `*/5 * * * *`（每 5 分钟，已通过 Cloudflare API 确认登记） |
> | 时间窗 | 北京时间 07:00–24:00（见第二节「额度限制」） |
> | 实测 | 后端休眠时返回 `{"ok":false,"booting":true,...}`；启动完成后返回 `{"ok":true,"status":200,"ms":187}` |
>
> 以下两条路仍然保留作为参考（换账号/迁移时用），当前生效的是 Cloudflare Worker。
> - **二、Cloudflare Worker + Cron Trigger**
> - **三、Supabase pg_cron**（本项目已在用 Supabase，不需要新账号）

## 一、问题是怎么定位的

2026-09-22 真机反馈：连续两次登录，每次都等满 150s 后失败。

| 排查项 | 实测结果 | 结论 |
|---|---|---|
| 后端端点是否正常 | 实例热态下 `/api/info`、`/api/health`、`/actuator/health` 均 **1s 内返回 200** | 端点无问题 |
| CORS 是否放行 Pages 域名 | 预检正常，前端可直连 | 非 CORS 问题 |
| 冷启动耗时 | 休眠态发起请求，**355s 后才拿到首个 200 响应** | 远大于前端 150s 预算 |
| 保活工作流是否在跑 | 配的是每 5 分钟，**实际间隔 3~6 小时** | 保活形同虚设 |

**冷启动耗时的完整分布**（用保活工作流 30 次运行记录反推，口径见下）：

```
样本 17（成功运行）  最小 209s   P25 233s   中位 258s   P75 292s   最大 406s
另有 8 次未在唤醒预算内完成（≥480s）
```

> 冷启动的测法（可复现，三种互相印证）：
> - **轮询法**：让实例自然休眠 15 分钟以上，然后每 25s 轮询一次 `/api/health`，
>   记录首个返回 200 的时刻 → **355s**。
> - **补发请求法**：T+0 发一个请求（它会触发实例拉起，但连接被 Render 挂住、长时间无响应）；
>   再在 T+300 发第二个请求，它于 **T+337.8** 返回 401 → 说明实例约 338s 时已可服务。
>   单看第一个请求会误判为「永远起不来」，必须补发第二个才能定位「何时就绪」。
> - **工作流时长法**（可长期免费用）：保活工作流每次运行本身就是一次冷启动实测，
>   跑 `node scripts/coldstart-report.mjs` 即可读出来，详见下文。
>
> ⚠️ 统计口径：**失败的运行时长等于当时的唤醒预算，只能给出下界，必须剔除**，
> 否则会把真实冷启动**低估**（2026-09-18 那批 ~216s 的失败就是当时 210s 预算的产物，
> 不是 216s 的冷启动）。脚本已按此口径处理。

保活工作流（`.github/workflows/keepalive.yml`）的真实运行间隔（取自 GitHub API）：

```
2026-09-22T10:05:13Z
2026-09-22T05:22:13Z   ← 间隔 4h43m
2026-09-22T00:42:12Z   ← 间隔 4h40m
2026-09-21T18:23:56Z   ← 间隔 3h39m
2026-09-21T12:57:00Z   ← 间隔 5h27m
2026-09-21T06:19:04Z   ← 间隔 6h38m
```

**GitHub Actions 的 `schedule` 触发器是「尽力而为」调度**，在公共仓库上会被大幅降级，
几分钟的 cron 实际可能几小时才跑一次。这不是配置写错，靠改 cron 表达式无法修复 ——
必须换成准点触发的外部定时器。

## 二、推荐方案：Cloudflare Worker + Cron Trigger

前端已经托管在 Cloudflare Pages，用同一账号加一个 Worker 即可，**无需注册新服务**，
免费套餐支持最小 1 分钟的 Cron Trigger。

### 方式 A：脚本部署（推荐，可重复执行）

```bash
# Git Bash / macOS / Linux
CF_API_TOKEN=你的token node scripts/deploy-keepalive-worker.mjs

# PowerShell
$env:CF_API_TOKEN="你的token"; node scripts/deploy-keepalive-worker.mjs
```

脚本会自动完成「上传 Worker → 设置 Cron Trigger → 打印验证地址并实测一次」，
重复执行是幂等的（覆盖同名 Worker）。

**token 只需一条权限**：`Account → Workers Scripts → Edit`。

> ⚠️ **安全**：脚本只从环境变量读 token，**绝不写入文件**；也请勿把 token 提交进仓库。
> 不要用 Global API Key（权限过大），用完即到 Dashboard 吊销。

### 方式 B：Dashboard 手动操作

1. Cloudflare Dashboard → **Workers & Pages** → **Create** → **Worker**
2. 把 `scripts/keepalive-worker.mjs` 的**全部内容**粘进在线编辑器 → **Deploy**
3. 进入该 Worker → **Settings** → **Triggers** → **Cron Triggers** → **Add Cron Trigger**
   - 表达式：`*/5 * * * *`（每 5 分钟；代码里 `CRON_EXPRESSION` 常量同值，可直接复制）
4. **验证**：浏览器打开该 Worker 域名（GET /），应返回类似
   ```json
   { "cron": "*/5 * * * *", "ok": true, "status": 200, "ms": 1180 }
   ```
   看到 `ok: true` 即表示保活链路通了。

### 改窗口前先跑测试

时间窗过滤逻辑写在 Worker 的 JS 里，**写错的代价是整个站点被暂停**
（恒返回 true → 后端 7×24 常驻 ≈730h/月 → 撑爆 750h → Render 暂停所有免费服务）。
所以有 6 条单元测试兜着，其中一条是**额度守卫**（每日保活小时数必须 ≤ 20）：

```bash
node --test scripts/keepalive-worker.test.mjs
```

覆盖：全天 24 小时判定、窗口边界（06:59/07:00/23:59/00:00）、跨天窗口（07:00→次日01:00）、
`WARM_ALL_DAY` 开关、额度守卫、以及「定时间隔必须小于 Render 的 15 分钟休眠阈值」。
**调整 `WARM_WINDOW_*` 或 `CRON_EXPRESSION` 后务必重跑。**

### ⚠️ 必须知道的额度限制（决定保活窗口怎么设）

Render 免费层每个 workspace **每月共 750 instance hours**，**用超了会把所有免费服务
一起暂停到次月** —— 注意是「所有」，不只是被保活的那个。所以窗口必须按额度倒推。

| 策略 | 月消耗 | 剩余给 embedding | 结论 |
|---|---|---|---|
| 主后端 7×24 常驻 | ≈730h | ≈20h | ❌ 一次重部署重叠就可能超额 |
| 主后端 + embedding 都常驻 | ≈1460h | 负数 | ❌ **必然超额，全站被暂停** |
| **主后端 07:00–24:00（默认）** | **≈517h** | **≈233h** | ✅ 足够日常使用 |
| 主后端 07:00–次日01:00 | ≈540h | ≈210h | ✅ 也可接受 |

**那 233h 余量怎么够用**：embedding 服务是**按需唤醒**的（每次唤醒后保活 15 分钟），
233h ÷ 0.25h ≈ **930 次唤醒/月（≈30 次/天）**，日常使用绰绰有余。
主后端常驻期间没人用 embedding，它就一直睡着、不消耗额度 —— 这正是**只保活主后端**
的原因：embedding 只影响 RAG/AI 能力，不影响登录。

> ✅ **为什么可以放心只保活主后端**（2026-09-22 实测两个服务的冷启动）：
>
> | 服务 | 冷启动 | 热态 | 影响 |
> |---|---|---|---|
> | 主后端（Spring Boot） | **209~488s** | 6~8s | 登录卡死 → **必须保活** |
> | embedding（Python/ONNX，模型已烘焙进镜像） | **23.9s** | 0.9~1.2s | 仅 RAG 首次提问慢 20 余秒，远在 AI 超时（180s）内 → **不值得花额度保活** |
>
> 两者相差一个数量级：embedding 是轻量 Python 服务，主后端是 Spring Boot 全家桶。
> 所以「把 750h 额度全花在主后端上」是划算的取舍。

（月按 30.44 天折算；重部署开销约 10 分钟/次，20 次/月 ≈ 3h，可忽略。）

因此 `keepalive-worker.mjs` 的默认配置是：

- **只保活主后端**；
- **北京时间 07:00–24:00 保活**（`WARM_WINDOW_START_HOUR=7` / `END_HOUR=24`）。

想覆盖到凌晨，把 `WARM_WINDOW_END_HOUR` 设为 `25`（次日 01:00，≈540h/月）；
想 7×24，把 `WARM_ALL_DAY` 设为 `true`（≈730h/月，余量仅约 20h，**风险很高**）。

## 三、更省事的替代：用 Supabase 的 pg_cron（本项目已在用 Supabase）

如果不想再开一个 Cloudflare Worker，可以直接用**数据库自己的定时器**：
Supabase 内置 `pg_cron`（调度）+ `pg_net`（发 HTTP 请求）。**不需要任何新账号、
不需要分享任何 token**，只要在 Supabase Dashboard → SQL Editor 里跑一次下面的 SQL。

```sql
-- 1) 启用扩展（已启用则跳过）
create extension if not exists pg_cron;
create extension if not exists pg_net;

-- 2) 每 5 分钟 ping 一次后端健康检查端点
--    ⚠️ 时间窗按北京时间 07:00–24:00 限制，理由见下方「额度限制」：
--       CST = UTC+8，故 07:00 CST = 23:00 UTC，24:00 CST = 16:00 UTC
--       → 小时字段为 23 与 0-15
select cron.schedule(
  'render-backend-keepalive',
  '*/5 23,0-15 * * *',
  $$
    select net.http_get(
      url := 'https://interview-guide-backend.onrender.com/api/health',
      timeout_milliseconds := 30000
    )
  $$
);
```

**验证**：

```sql
select jobid, jobname, schedule, active from cron.job;                  -- 任务是否登记
select status, start_time, end_time from cron.job_run_details
  order by start_time desc limit 10;                                    -- 最近几次执行
select status_code, content from net._http_response
  order by created desc limit 5;                                        -- 实际 HTTP 结果
```

**回滚**（不想要了就跑这一句）：

```sql
select cron.unschedule('render-backend-keepalive');
```

> 为什么时间窗写成 `23,0-15`：免费层每月 750 instance hours，**超额会暂停所有免费服务**，
> 而主后端 7×24 常驻就要 ≈730h（余量仅约 20h）。限制在北京时间 07:00–24:00 约 517h/月，
> 余约 233h 留给 embedding 服务（≈930 次按需唤醒/月），日常使用足够。
> 想覆盖到凌晨就把表达式改成 `*/5 23,0-16 * * *`（次日 01:00，≈540h/月）；
> 想 7×24 就改成 `*/5 * * * *`，但余量只剩约 20h，**风险很高**。

### 📊 用真实用量验证过（2026-09-22）

不是纸上推算 —— 已查 Render 账单页与 Worker 指标，实测数据如下：

| 项 | 实测值 | 说明 |
|---|---|---|
| 本月已用实例小时 | **113.85 / 750**（9/1–9/22，≈5.3h/天） | 此前**无保活**，纯按需唤醒 |
| 启用保活后推算 | 主后端 17h/天 + embedding ≈1.3h/天 ≈ **18.3h/天 ≈ 557h/月** | **余量约 193h/月** |
| 9 月全月预计 | ≈270h | 远低于 750h |
| Worker 触发 | Triggers = 1；24h 内 **Invocations 14、Errors 0**、CPU 959µs | 定时确实在工作 |
| 后端保活效果 | 43 分钟无人工访问仍 **1.0–2.7s** 响应 | 若休眠需 ≈4 分钟 |

**结论：额度安全，日常使用不受影响。**

> 🔍 复核配置时的一个坑：读 Render 设置页的**表单字段值必须用可访问性快照**
> （`playwright-cli snapshot`），**不能用 `document.body.innerText`** ——
> `innerText` 不含 `<input>` 的 value，会让人误判成「路径没配上」。
> 实测 `document.body.innerText.includes('embedding-service/**')` 返回 `false`，
> 而同一页面快照显示 `textbox: embedding-service/**`。
>
> 已核实两个服务的构建过滤**都已生效**：主后端 `backend/**`、embedding `embedding-service/**`，
> 且都是 `autoDeployTrigger = On Commit`。
>
> ⚠️ 一个前提：Supabase 免费项目**连续 7 天无活动会被暂停**，暂停后 cron 也随之停止。
> 本项目数据库在日常使用中会持续产生活动，正常不会触发；但若长期无人使用，
> 这个保活会跟着失效（那种情况下站点本身也已经没人访问了）。

## 四、其他备选方案

若不想动 Cloudflare：

| 方案 | 间隔 | 说明 |
|---|---|---|
| [cron-job.org](https://cron-job.org) | 1 分钟 | 免费，准点，注册即用 |
| [UptimeRobot](https://uptimerobot.com) | 5 分钟 | 免费，兼做可用性监控与告警 |
| Render 自带 Cron Job | — | **免费层不支持**，需付费 |

任选其一，URL 填 `https://interview-guide-backend.onrender.com/api/health`，间隔 5 分钟。

## 五、`.github/workflows/keepalive.yml` 还留着吗

留着，作为**尽力而为的兜底**（它偶尔真能跑起来，聊胜于无），但**不要把它当作保活主力**。
它的文件头已记录本次实测到的限流事实，避免后人再被「配置了每 5 分钟」误导。

## 六、减少「无意义重启」带来的冷启动

保活解决的是「闲着睡着了」，但还有一类冷启动是**自己造出来的**：只要推送到 `main`，
Render 就会重新构建并重启后端 —— 哪怕这次只改了前端或文档。

`render.yaml` 已修正为按路径过滤：

```yaml
    autoDeployTrigger: commit      # 取代已废弃的布尔型 autoDeploy
    buildFilter:
      paths:
        - backend/**               # 主后端：仅 backend 目录变更才部署
```

| 推送内容 | 修复前 | 修复后 |
|---|---|---|
| `frontend/**`、`docs/**` | 后端重建 + 重启（白吃一次冷启动） | 后端不动 ✅ |
| `backend/**` | 后端重建 + 重启 | 后端重建 + 重启（符合预期） |

> ⚠️ `buildFilter` 在蓝图同步时会**完全替换**服务上已有的过滤设置；若整个字段被省略，
> 已有设置会被清空为空列表 —— 所以必须显式写全，别只写一半。
> 另：Render 蓝图默认**自动同步**，改完 `render.yaml` 推上去即生效（会带来一次部署）。

## 七、如果还是遇到冷启动

前端已按真实冷启动时长（8 分钟上限）设计容错：

- 页面一打开就预热后端（`main.ts` → `prewarmBackend()`），用户输入账号密码的时间就在启动实例；
- 等待期间显示**实时秒数**与真实预期（「约 1-8 分钟」），不再谎报「1-2 分钟」；
- 等待中切换标签页/网络恢复会自动补探测，不会停在假死状态；
- 超时后按钮是**「继续等待」**（追加预算，已等待时间不清零），而不是让人刷新页面重来。

> ✅ **三种状态都已在真实浏览器里验证过**（2026-09-22）：
>
> | 状态 | 实测表现 |
> |---|---|
> | 后端热态 | **不显示任何提示**（保活生效，用户无感） |
> | 探测中 | 琥珀色提示「后端服务正在启动（免费实例冷启动约 1-8 分钟），已等待 12s…」，秒数每秒跳动 |
> | 预算耗尽 | 「后端服务仍在启动中（已等待 483s）…点击「继续等待」即可，已填内容不会丢失」+ **「继续等待」按钮出现** |
>
> **验证方法（不必真的等后端休眠）**：用请求拦截伪造「后端不可达」——
> `playwright-cli route "**/api/info*" --status=503 --body="warming"`。
> ⚠️ 必须用 503 + **非业务 JSON**：探测逻辑把「404 等其它状态码」视为已就绪，
> 用 404 伪造不出冷启动效果。
>
> ⚠️ 排查时注意一个假象：若浏览器窗口被切到后台，`installWakeRecovery()` 的
> `visibilitychange` 钩子会在窗口重新可见时触发重试并**续期预算**，
> 表现为「预算到期后仍显示正在启动、秒数继续涨」。这是设计意图（用户切回来时继续等），
> **页面保持前台时预算会正常到期并进入失败态**。

需要进一步缩短冷启动，可参考 `render.yaml` 中 `JAVA_OPTS` 的说明调整 JVM 启动参数
（如加 `-XX:TieredStopAtLevel=1` 换取更快的启动），但**必须实测验证**后再上线。

## 八、缩短冷启动本身（已应用的优化 + 如何验证）

保活解决「闲着睡着了」，但冷启动本身的耗时也该压。已确认启动期**没有阻塞式外部调用**
（唯一的 `@PostConstruct` 外部依赖是 `UserBanRegistry` 读 Redis，而 prod 已排除 Redis
自动装配，会直接短路返回），因此耗时几乎全在 **JVM 启动 + Spring 上下文刷新**这一段
纯 CPU 工作上 —— 而免费层实例的 CPU 被严重限制，这正是它慢的根因。

据此已调整 `JAVA_OPTS`（`render.yaml` 与 `backend/Dockerfile` **两处同步**，
运行时以 render.yaml 的环境变量为准）：

| 参数 | 说明 |
|---|---|
| `-XX:TieredStopAtLevel=1` | **新增**。JIT 只用 C1、不创建 C2 编译线程，省下 CPU 争抢 |
| `-XX:MaxRAMPercentage=50.0` | **移除**。与 `-Xmx220m` 并存属死配置（显式 `-Xmx` 优先），留着会误导 |

**如何验证效果**（不需要登录 Render，完全免费）：

```bash
node scripts/coldstart-report.mjs          # 最近 30 次运行
node scripts/coldstart-report.mjs --limit 50
```

原理：保活工作流每次运行本身就是一次冷启动实测，其 Run 时长 ≈ 冷启动耗时 + 约 6s 固定开销。
脚本会打印冷启动分布并与基线（样本 17 / 中位 258s）对比。

> ⚠️ 两个坑：
> 1. **失败的运行必须剔除** —— 它的时长等于当时的唤醒预算，只是下界，
>    算进去会把真实耗时低估（脚本已处理）。
> 2. **单次波动可达 100s+**，样本 < 5 次不要下结论；脚本内置 ±60s 噪声带，
>    在带内会明确输出「基本持平」而不是硬给结论。

若优化后仍然偏慢，下一步可考虑（**均需实测**，别直接上生产）：
`spring.main.lazy-initialization`（收益大但会改变 Bean 初始化时序，风险高）、
或迁移到无冷启动的托管方式（如 Oracle Cloud Always Free 自建 VM）。

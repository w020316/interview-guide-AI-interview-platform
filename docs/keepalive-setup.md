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
> | 窗内判定 | 21 条单元测试 + 一致性检查 + 线上 blob 哈希比对 + 线上实测，见「九、时间窗过滤的验证」 |
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
测试分两层，**第二层才是真正的闸门**：

```bash
node --test scripts/keepalive-worker.test.mjs       # 11 条：判定函数 + 闸门行为 + 额度守卫
node --test scripts/check-deployed-worker.test.mjs  # 10 条：线上↔仓库 指纹比对 + multipart 剥壳
node scripts/check-window-consistency.mjs           # 窗口在 JS 与 Actions YAML 两处必须一致
```

| 层 | 覆盖 | 能抓住什么 |
|---|---|---|
| ① 判定函数层 | 全天 24 小时判定、边界（06:59/07:00/23:59/00:00）、跨天窗口（07:00→次日01:00）、`WARM_ALL_DAY` 开关、额度守卫（实际小时数须在 8~20） | 判定式本身写错 |
| ② **闸门行为层** | 冻结时钟 + 桩掉网络，驱动真实的 `worker.fetch()`（cron 与手动访问同一条路径），断言窗外**一次请求都不发**、窗内恰好一次、边界时刻取舍正确 | **调用点被改坏** —— 只测 ① 会漏掉这条路 |

> 🔬 ② 不是「锦上添花」，是实测出来的必需项（2026-09-22）：把 `warm()` 里的
> `inWarmWindow(now)` 改成 `inWarmWindow(now, 0, 24)`（生产等效恒为 true、后端 7×24 常驻），
> **① 的测试全部照常通过** —— 那些用例都在用一个不涉及调用点的默认参数。
> 加上 ② 之后，同样的改动会让 2 条测试失败，且失败信息直接点明「会撑爆额度导致全站被暂停」。
>
> 另外：`WARM_ALL_DAY` 一旦被打开，① 也会失败 —— 但失败信息是「全天判定不正确」，
> 不会提到额度。所以另有一条**指名道姓**的断言（「生产开关：WARM_ALL_DAY 必须为 false」），
> 避免维护者去改测试而不是改开关。

**调整 `WARM_WINDOW_*` 或 `CRON_EXPRESSION` 后，上面两条命令都必须重跑。**

> ⚠️ 窗口策略有**两处实现**，改窗口时别只改一处：`scripts/keepalive-worker.mjs`（主力）
> 与 `.github/workflows/keepalive.yml` 的时间窗守卫（兜底）。两处漂移时额度会按**较宽的那处**
> 消耗，而两边各自的测试都只盯着自己那份文件、不会变红 —— `check-window-consistency.mjs`
> 就是为这个盲区写的。

### 📏 窗口守卫的验证方法（可复现）

`keepalive.yml` 里那段守卫是 bash，光看数字不算验证。用 `date` 桩把它**真的执行**一遍、
对 24 个小时逐一核对（实测 24/24 正确：CST 07:00–23:59 为窗内、00:00–06:59 为窗外）：

```bash
# 抽取守卫的 run 文本 → 用一个假的 date -u +%H 驱动它 → 比对 in_window 输出
# 参考实现见本次记录：python + pyyaml 取 steps[0].run，bash 里覆写 date() 后 source
```

守卫的**失败方向是「跳过」**：解析不出小时数时按窗外处理。少一次兜底保活无所谓
（主力是 Cloudflare Worker），白吃额度却会累积成超额。

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

> 🔴 **2026-09-22 补的一个漏**：这个兜底工作流此前**没有任何时间窗过滤**，24 小时都在
> ping —— 而它对「几点该醒」完全无感。查 GitHub API 的真实运行记录，最近 23 次定时运行中
> **9 次落在 07:00–24:00 之外**（CST 01/02/03/05/06 点）：
>
> ```
> 2026-09-21T22:03:39Z → CST 06 点   ⚠️
> 2026-09-21T18:23:56Z → CST 02 点   ⚠️
> 2026-09-20T21:30:43Z → CST 05 点   ⚠️
> 2026-09-20T19:12:31Z → CST 03 点   ⚠️
> 2026-09-20T16:57:26Z → CST 00 点   ⚠️
> 2026-09-19T21:40:33Z → CST 05 点   ⚠️
> 2026-09-19T19:28:17Z → CST 03 点   ⚠️
> 2026-09-19T17:23:30Z → CST 01 点   ⚠️
> ```
>
> 代价有两层：① 每次窗外运行白唤醒一次后端（冷启动数分钟 + 保活 15 分钟 ≈ 0.3~0.4
> instance hours），按 ~30% 越窗比例折算约 **15h/月**，纯浪费且啃掉留给 embedding 的余量；
> ② 更隐蔽 —— 它会在凌晨随机把后端**弄热**，使「窗外应该冷」的验证无法成立。
>
> 已修：作业第一步判定时间窗，窗外直接跳过、**不发出任何请求**；两个会发请求的步骤
> 都挂上 `if: steps.window.outputs.in_window == 'true'`。验证见第二节「窗口守卫的验证方法」。

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

## 九、时间窗过滤的验证（2026-09-22）

「时间窗写错 → 后端 7×24 常驻 → 撑爆 750h → **所有**免费服务被暂停」是本方案最严重的
失败模式，所以单独做了四层验证。**代码对 ≠ 线上行为对**，两者验证缺一不可。

### 9.1 线上跑的是哪一版代码（已用 blob 哈希钉死）

Worker 是用 `scripts/deploy-keepalive-worker.mjs` 从仓库文件整份上传的，因此线上内容
应当等于某个提交时的文件。**2026-09-22 实测确认了这一条，且是逐字节级别的**：

```bash
# 用已登录的浏览器会话取回线上脚本原文（不需要 token，方法见 9.5），存成 deployed.mjs
sha256sum deployed.mjs repo中该版本.mjs        # 或用 git 的对象哈希比对
git hash-object deployed.mjs                    # → da0c0f7fd58f2cf7d0bfbcc47b3fd9a54523363c
git rev-parse df6eaa1:scripts/keepalive-worker.mjs   # → 同一个 blob
```

| 项 | 实测结论 |
|---|---|
| 线上脚本内容 | **6891 字节，sha256 与仓库 `df6eaa1` 的 blob 完全一致 —— 逐字节相同** |
| 部署时间 | Cloudflare 侧 `modified_on = 2026-09-22T13:38:19Z`（CST 21:38），与 `df6eaa1`（21:39 提交）吻合 |
| 线上版本的常量 | `START=7` / `END=24` / `WARM_ALL_DAY=false`，`(getUTCHours()+8)%24` 换算 CST，探测超时 25s |
| 与当前 HEAD 的差异 | **只有注释、export、默认参数**（`git diff df6eaa1 HEAD` 可见），判定逻辑与常量逐字相同 |
| 线上已注册的 Cron Trigger | **`*/5 * * * *`**（`created_on 13:34:37Z`、`modified_on 13:38:19Z`） |

> ✅ 由此可判定：**「有人直接在 Dashboard 手改过脚本」这个假设被排除**（哈希对得上，
> 手改必然改变内容）。因此 9.2 的单测、9.4 的线上行为，对应的就是线上那一份代码。
>
> 之所以**没有重新部署到 HEAD**：差异仅为注释与 export，指纹与行为完全一致，
> 为一个无行为差异的改动重传一次生产 Worker 不划算。若要字节级对齐，
> `deploy-keepalive-worker.mjs` 一条命令即可（需 `Workers Scripts → Edit` 权限）。

### 9.2 代码层：找出并补上了「闸门无覆盖」的洞

见第二节「改窗口前先跑测试」。核心事实：**只测判定函数的套件，在调用点被改成恒真时
依然全绿**（已实测），所以补了「闸门行为层」的 3 条测试。

### 9.3 兜底工作流：发现它根本没有时间窗

见第五节。23 次运行 9 次越窗，已修，并补了 24 小时逐时验证。

### 9.4 线上行为（一冷一热的对照）

```bash
node scripts/verify-window.mjs                 # 窗内探测（免费）；窗外只做零成本核对
node scripts/verify-window.mjs --probe-backend # 窗外也探测后端（**会真的唤醒实例**，按需才用）
VERIFY_FORCE_CST_HOUR=3 node scripts/verify-window.mjs   # 测试用：白天也能走一遍「窗外」分支
```

| 时点 | 期望 | 实测 |
|---|---|---|
| CST 22:xx（窗内） | 热态 | ✅ HTTP 200，**1.07s** |
| CST 23:05（窗内） | 热态 + Worker 应探测 | ✅ Worker 自述 `{"cron":"*/5 * * * *","ok":true,"status":200,"ms":304}` |
| CST 00:02（窗外） | Worker 应返回 `skipped` | ✅ Worker 自述 `"workerSkipped":"yes"`，**未探测后端**（零成本）→ `pass: true` |
| CST 03:1x（窗外） | 同上，且兜底工作流不得在窗外发请求 | ⏳ 云端每夜自动取证，见 9.7 |

**Worker 自述是最直接的线上证据，而且零额度成本**（窗外它直接返回、根本不碰后端）：

```bash
# 本机经系统代理可达 workers.dev（直连与 WorkBuddy 托管代理都不通，见 9.5）
curl -s -x http://127.0.0.1:7890 https://render-keepalive.1181264839.workers.dev/
# 窗内期望 → {"cron":"*/5 * * * *","ok":true,"status":200,"ms":...}
# 窗外期望 → {"cron":"*/5 * * * *","skipped":true,"reason":"outside warm window","cstHour":3}
```

> ✅ **`verify-window.mjs` 默认不花额度**（2026-09-22 改）：窗内探测是免费的（后端本就被保活着，
> 不会额外拉起实例）；**窗外默认不探测**，改用 Worker 自述做零成本核对 —— 因为探测会把休眠实例
> 拉起来（≈0.25 instance hours），而本脚本存在的意义恰恰是保护额度，为了验证额度安全而消耗额度
> 方向反了。确实要在窗外测后端冷热时显式加 `--probe-backend`。
> 每夜的自动观测见 9.7（跑在云端，同样零成本）。
> 凌晨那次必须等兜底工作流的时间窗守卫**先上线**再做，否则它会随机把后端弄热，
> 让「窗外应该冷」的结论不成立（这正是 9.3 那个漏的第二个代价）。

### 9.5 核对线上脚本与已注册定时

`scripts/check-deployed-worker.mjs` 拉取**线上脚本原文**与**已注册的 Cron Trigger**，抽出关键
指纹（窗口起止 / 开关 / 定时 / 探测端点 / 超时 / 后端地址）与仓库逐项比对，并检查**闸门顺序**
（`if (!inWarmWindow(...))` 必须在所有 `await fetch(` 之前 —— 顺序反了等于没有时间窗，
而常量看起来完全正常）。

```bash
# 走 API：token 只需 Account → Workers Scripts → Read
CF_API_TOKEN=xxx node scripts/check-deployed-worker.mjs
# 离线：文件可以是纯脚本，也可以是 CF 下载端点的**原始 multipart 响应**（会先剥包装）
node scripts/check-deployed-worker.mjs --source-file ./deployed-worker.mjs
```

覆盖这个第三种盲区：**线上脚本内容本身和仓库不一样**（例如有人直接在 Dashboard 手改）。
单元测试证明「仓库里的代码对」，`verify-window.mjs` 证明「线上行为对」，但两者都可能
在「脚本被手改、而改动恰好不影响当前时刻」时给出假绿灯。

> 比对逻辑（`extractFingerprint` / `diffFingerprints` / `extractModuleSource`）是纯函数，
> 已由 `scripts/check-deployed-worker.test.mjs` **10 条**离线测过 —— 含
> **「线上实际部署版本与仓库指纹一致」**、三类漂移必须被报出来，以及 4 条 multipart 剥壳。

#### 不需要 token 的取数路径（实测可行）

`Account → Workers Scripts → Read` 这种只读 token 其实可以不开：**用已登录的浏览器会话，
在 dash 页面内直接调同一套 API**（凭 Cookie 鉴权）。实测步骤：

1. 起一个带调试端口的 Edge，打开 `https://dash.cloudflare.com/`（若是 GitHub/邮箱 SSO，
   需要本人过一下登录）；
2. 在页面上下文里发请求，`credentials: 'include'` 即可：

```js
// 账号 ID
fetch('/api/v4/accounts', { credentials: 'include' }).then(r => r.json())
// 已注册定时（这就是「定时到底登记了没有」的答案）
fetch('/api/v4/accounts/<ACCOUNT_ID>/workers/scripts/render-keepalive/schedules', { credentials: 'include' }).then(r => r.json())
// 线上脚本原文 —— ⚠️ 返回的是 multipart/form-data，需剥掉包装才是脚本正文
fetch('/api/v4/accounts/<ACCOUNT_ID>/workers/scripts/render-keepalive/content/v2', { credentials: 'include' }).then(r => r.text())
```

   剥包装可以直接交给脚本：把响应原文存成文件，`--source-file` 会识别并剥掉
   （这就是 `extractModuleSource()`，已按真实响应校验）。
3. 想留档的话把剥出的正文存成 `.mjs`，再和 `git cat-file blob <版本>:scripts/keepalive-worker.mjs`
   做逐字节比对（9.1 就是这么做的）。

> ⚠️ 从页面里取大字符串时注意：**别用会做换行转换的方式落盘**（Python 文本模式
> `read_text()/write_text()` 会把 CRLF 归一化），否则逐字节比对会失真 —— 用二进制读写。

### 9.6 验证状态（哪些已闭合、哪些靠什么兜）

| 项 | 状态 |
|---|---|
| 仓库代码正确（判定 + 闸门 + 额度守卫） | ✅ 21 条单测（11 + 10）+ 一致性检查，已进 CI 门禁 |
| 线上脚本内容 == 仓库 | ✅ **blob 哈希逐字节相同**（9.1） |
| 线上已注册的 Cron Trigger | ✅ 实测为 `*/5 * * * *`（9.1） |
| 线上窗内行为 | ✅ Worker 自述 `ok:true, ms:304`；后端热态 1.07s（9.4） |
| 线上窗外行为 | ✅ 已实测：CST 00:02 云端派发，Worker 自述 `skipped`、零成本未探测后端（9.4）；每夜 03:10 仍会自动取证（9.7） |
| 窗内会按时恢复 | ✅ 已实测：CST 22–23 点 Worker 正常探测、后端热态（9.4）；每晨 09:05 仍会自动取证（9.7） |

**已知局限**：

- 「本机出网要挑代理」：直连 `workers.dev` 超时、WorkBuddy 托管代理对该域名 CONNECT 502，
  **系统代理 `127.0.0.1:7890` 才通**。且 Node 的 `fetch` **不读** `HTTP_PROXY/HTTPS_PROXY`，
  脚本里的 Worker 探测在本机会失败，改用 `curl -x`。
- `check-deployed-worker.mjs` 里**带 Bearer 的那段 HTTP 调用本身**仍未实机跑过（没有 token）；
  但返回体形状已按真实响应验证（9.5），且 `--source-file` 是它的等价替代路径。

### 9.7 夜间观测放在云端（本机夜间关机）

**约束**：要验证「窗外确实没被保活」必须在凌晨取数，而**开发机夜间是关机的**。
任何跑在本机的定时任务在那种情况下要么不执行、要么开机后延迟触发 —— 后者更糟：
它会在错误的时间点上套用错误的期望，既可能假失败也可能假通过。

**解法**：`.github/workflows/keepalive-observe.yml`，跑在 GitHub 托管 runner 上，**零额度成本**。

| 项 | 说明 |
|---|---|
| 触发 | `19:10 UTC`（=03:10 CST，期望窗外）+ `01:05 UTC`（=09:05 CST，期望窗内）；也支持手动 `workflow_dispatch` |
| 期望来源 | **按运行时的真实 CST 小时判定**，而不是按哪个 cron 触发 —— GitHub 的 schedule 是「尽力而为」调度、实测会晚数小时（见 `keepalive.yml` 头部），跑晚了就自动改测另一支，不产生假失败 |
| 观测项 | ① Worker 自述是否 `skipped` ② 兜底工作流的**耗时指纹** ③ 后端冷/热（**仅窗内**）④ 额度预算 |
| 断言 | 窗外：Worker 必须 `skipped`、且确认兜底工作流没在窗外发请求；窗内：Worker 必须探测、后端必须热态。不符即 `::error::` + 作业失败 |
| 留档 | 结果写入 run 摘要（permanent）并上传 artifact（保留 90 天） |
| 成本 | **0**。窗外**不探测后端**（见下），也不额外拉起实例；窗内探测是免费的（那时后端本就该被保活着） |

#### 为什么是零成本：不去叫醒它，而是证明没人叫醒它

最初版本在窗外「直接探测后端，看它是不是冷的」。代价是**任何请求都会把实例拉起来**，
每次白吃约 0.25 instance hours（≈7.5h/月）—— 而这份观测的存在意义恰恰是保护额度，
**为了验证额度安全而消耗额度，方向反了**。现在改成验证「所有自动唤醒源都没动它」：

1. **Worker 自述必须 `skipped`** —— 窗外它直接返回，根本不发请求（零成本，最硬的证据）。
2. **兜底工作流的耗时指纹**：它若也在窗外跑过，看它的**运行耗时**。
   跳过时整个作业只在 runner 上跑十几秒（实测 8s）；真去唤醒后端会因冷启动跑数分钟
   （实测 318~488s）。用 `> 120s` 判定「它是不是真的发了请求」—— 零成本，且查的是**原因**而非症状。
3. 两者都成立 + 深夜无人访问 ⇒ 后端处于冷态。**不直接探测，因此不产生任何实例小时。**

两条实现上的注意（都踩过）：

- **只看最近 4 小时的兜底运行**。守卫是 2026-09-22 才加的，此前窗外运行会真的唤醒后端
  （实测 318~488s）；把历史一起算进来会天天误报，而要回答的是「刚才那几个小时有没有东西把它弄醒」。
- **解析不了的行不能让检查静默通过**。最初用 `[[ "$secs" =~ ^[0-9]+$ ]] || continue`：
  格式一变（或行尾多一个 CR）就会被当成「没有窗外运行」而给出绿灯。现在统计坏行数，
  有坏行即判定「无法确认」→ 窗外观测直接失败。

#### 额度预算（每次观测都会打印）

观测摘要里会给出一行预算：直接**复用被单测覆盖的 `effectiveWarmHoursPerDay()`** 读取窗口配置，
算出「保活 xx h/天 ≈ xxx h/月 ｜ 750h/月 → 余 xxx h」。当前口径：

```
保活 17h/天 ≈ 517h/月 ｜ 免费层 750h/月 → 余 233h 留给 embedding 按需唤醒（≈930 次唤醒/月）
```

> 🔬 这套断言本身也验过（否则就是「检查器坏了还给绿灯」）：
> - 期望映射用 `date` 桩逐小时扫描 → **24/24 正确**（CST 07:00–23:59 为 inside）；
> - 脚本体实跑走通「窗内」支（云端派发，实拿到 Worker 自述与后端耗时）；
> - 兜底耗时判定抽出来用构造数据测 **6/6**：历史 318s 运行报错、9s 跳过通过、
>   窗内运行忽略、空输入通过、CRLF 行尾不误判、畸形行导致失败；
> - **抓到过两个真缺陷**：① 最初把「Worker 请求超时/无响应」当成 `skipped=no`，窗内支照样判 ✅
>   （输入缺失被误读成通过）；② 兜底记录里解析不了的行被静默跳过，等价于「没有窗外运行」。
>   两处都改成「拿不到/解析不了 → 明确失败」。
>   （排查途中还三次败在自己的测试用法上：CRLF 夹具、`sed -i` 没改成功、抽片段把 `if/else` 截断 ——
>   每次都是先确认「是测试错了还是被查对象错了」才继续。）

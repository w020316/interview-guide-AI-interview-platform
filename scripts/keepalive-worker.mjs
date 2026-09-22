/**
 * Render 后端保活 Worker（Cloudflare Workers + Cron Trigger）
 *
 * ── 为什么需要它 ─────────────────────────────────────────────────────
 * 后端在 Render 免费层，15 分钟无请求即休眠，而冷启动实测 338s / 355s（≈6 分钟）。
 * 项目原本用 GitHub Actions 的 `schedule` 每 5 分钟 ping 一次保活，但
 * **GitHub 对定时工作流只做「尽力而为」调度，实测被限流到每 3~6 小时才跑一次**
 * （见本仓库 Actions 历史：2026-09-22 的间隔为 4h43m / 4h40m，09-21 有 6h38m 的间隔）。
 * 结果是后端几乎一直处于休眠，每个用户都在吃冷启动。
 *
 * Cloudflare Workers 的 Cron Trigger 是**准点触发**（最小 1 分钟粒度，免费套餐可用），
 * 且本项目前端已托管在 Cloudflare Pages，无需再注册新服务。
 *
 * ── 部署方式（约 2 分钟）─────────────────────────────────────────────
 * 1. Cloudflare Dashboard → Workers & Pages → Create → Worker
 * 2. 把本文件全部内容粘进在线编辑器 → Deploy
 * 3. 进入该 Worker → Settings → Triggers → Cron Triggers → Add Cron Trigger
 *    表达式填「每 5 分钟」的那条：分钟位为 星号 斜杠 5，其余三位为 星号
 *    （即 CRON_EXPRESSION 常量所示，直接复制即可）
 * 4. 访问该 Worker 的根路径（GET /）可手动触发一次，返回 JSON 便于验证
 *
 * 详见 docs/keepalive-setup.md
 *
 * ── 关于 Render 免费额度（务必先读）──────────────────────────────────
 * Render 免费层每个 workspace 每月共 750 instance hours，**用超了会把所有免费服务
 * 一起暂停到次月**。所以保活窗口必须按额度倒推，不能想当然 7×24。
 *
 *   主后端 7×24 常驻            ≈ 730h/月  → 只剩约 20h，一次重部署重叠就可能超额 ❌
 *   主后端 + embedding 都常驻    ≈ 1460h/月 → 必然超额，全站被暂停 ❌
 *   主后端仅 07:00–24:00（本配置）≈ 517h/月 → 余约 233h ✅
 *
 * 那 233h 余量是留给 **embedding 服务**的：它按需唤醒（每次唤醒后保活 15 分钟），
 * 折算约 930 次唤醒/月（≈30 次/天），足够日常使用，且不会挤占主后端。
 * 主后端常驻期间没人用 embedding，它就一直睡着、不消耗额度 —— 这正是只保活
 * 主后端的原因（embedding 只影响 RAG/AI 能力，不影响登录）。
 */

/** 后端地址 */
const BACKEND = 'https://interview-guide-backend.onrender.com'

/**
 * Cron 表达式：每 5 分钟一次。
 * 写在代码里是为了让你在 Cloudflare 控制台能直接复制，不必回忆格式。
 * （5 分钟远小于 Render 的 15 分钟休眠阈值，留出 3 倍余量）
 */
const CRON_EXPRESSION = '*/5 * * * *'

/** 保活探测端点：无鉴权、轻量，与前端唤醒器使用同一入口族 */
const PROBE_PATH = '/api/health'

/**
 * 单次探测的子请求超时。
 *
 * ⚠️ 2026-09-22 实测踩坑：**不设超时会直接把 Worker 的 HTTP 入口打挂**。
 * 后端休眠时 Render 会把请求挂在连接上等实例启动（实测 200~480s），
 * 而 Worker 单次调用有墙钟上限，于是调用方看到的是
 * `ERR_CONNECTION_CLOSED` / 502 —— 看起来像脚本坏了，其实只是"后端正在启动"。
 *
 * 给 25s 超时后：超时即返回结构化的"仍在启动"结果，调用方一眼能看懂；
 * 而**保活效果不受影响** —— 请求已经到达 Render 并触发/维持了实例启动，
 * 下一个 5 分钟周期的定时任务就会命中一个正在启动或已就绪的实例。
 */
const PROBE_TIMEOUT_MS = 25000

/**
 * 保活时间窗（北京时间，UTC+8）。
 *
 * 07:00 → 24:00，即 17h/天 ≈ 517h/月，为 embedding 服务与重部署留出约 233h 余量
 * （算法见文件头「关于 Render 免费额度」）。
 *
 * 想覆盖到凌晨就设 endHour = 25（即次日 01:00，约 540h/月，余量约 210h）；
 * 想 7×24 常驻则设 WARM_ALL_DAY = true（约 730h/月，余量仅约 20h）——
 * 后者风险很高：**一旦超额，Render 会暂停你所有免费服务，不只是主后端**。
 */
const WARM_WINDOW_START_HOUR = 7
const WARM_WINDOW_END_HOUR = 24
const WARM_ALL_DAY = false

/** 供测试断言「线上生效配置」用；Worker 逻辑一律走上面的模块常量，不读这些导出 */
export { WARM_ALL_DAY, WARM_WINDOW_START_HOUR, WARM_WINDOW_END_HOUR }

export default {
  /** Cron Trigger 入口 */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(warm())
  },

  /**
   * HTTP 入口：便于手动验证（浏览器打开该 Worker 域名即可看到最近一次保活结果）。
   * 不对外暴露任何敏感信息，仅返回探测结果。
   */
  async fetch() {
    const result = await warm()
    return new Response(
      JSON.stringify({ cron: CRON_EXPRESSION, ...result }, null, 2),
      { headers: { 'content-type': 'application/json; charset=utf-8' } }
    )
  },
}

/**
 * 当前是否处于保活时间窗内（北京时间）。
 *
 * 参数可覆盖，纯粹为了**可测**（见 scripts/keepalive-worker.test.mjs）——
 * Worker 里始终用模块常量调用。这段逻辑一旦写错会非常危险：
 * 若恒返回 true，后端会 7×24 常驻 ≈730h/月，**可能撑爆 750h 额度并导致
 * Render 暂停所有免费服务**，所以必须有测试兜住。
 */
export function inWarmWindow(
  now,
  start = WARM_WINDOW_START_HOUR,
  end = WARM_WINDOW_END_HOUR,
  allDay = WARM_ALL_DAY
) {
  if (allDay) return true
  const cstHour = (now.getUTCHours() + 8) % 24
  // end > 24 表示跨天（如 start=7、end=25 即 07:00 → 次日 01:00）
  if (end > 24) return cstHour >= start || cstHour < end - 24
  return cstHour >= start && cstHour < end
}

/** 每天保活多少小时（供测试做额度守卫；跨天窗口也正确） */
export function warmHoursPerDay(start = WARM_WINDOW_START_HOUR, end = WARM_WINDOW_END_HOUR) {
  return end > 24 ? 24 - start + (end - 24) : end - start
}

/**
 * 每天**实际**保活多少小时 —— 把 `WARM_ALL_DAY` 开关也算进去，供额度守卫使用。
 *
 * 为什么要有它：光看窗口常量（17h/天）会漏掉「开关一开就是 24h/天」这条路。
 * 守卫必须盯住**实际生效值**，否则「恒为 true」会以另一种形式溜过去。
 */
export function effectiveWarmHoursPerDay(
  start = WARM_WINDOW_START_HOUR,
  end = WARM_WINDOW_END_HOUR,
  allDay = WARM_ALL_DAY
) {
  return allDay ? 24 : warmHoursPerDay(start, end)
}

/** 供测试断言用：当前生效的定时表达式 */
export const ACTIVE_CRON = CRON_EXPRESSION

/**
 * 执行一次保活。
 *
 * 注意：实例冷启动可能长达数分钟（实测 209~488s），因此子请求带
 * {@link PROBE_TIMEOUT_MS} 超时。**超时不算失败** —— 请求已经触发 Render 拉起实例，
 * 下一个 5 分钟周期的定时任务会命中一个正在启动/已就绪的实例。
 */
async function warm() {
  const now = new Date()
  const startedAt = Date.now()
  // ⚠️ 这里是**唯一**的窗口闸门，也是唯一的出口：下面那次 fetch 必须被它挡住。
  //    改动本行参数 = 直接改生产保活行为（写错会让后端 7×24 常驻 → 撑爆额度）。
  //    测试 scripts/keepalive-worker.test.mjs 里的「闸门行为」三条会在改错时失败。
  if (!inWarmWindow(now)) {
    return { skipped: true, reason: 'outside warm window', cstHour: (now.getUTCHours() + 8) % 24 }
  }
  const target = `${BACKEND}${PROBE_PATH}?_warm=${startedAt}`
  try {    const res = await fetch(target, {
      method: 'GET',
      redirect: 'follow',
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
    })
    const body = await res.text()
    return {
      ok: res.ok,
      status: res.status,
      ms: Date.now() - startedAt,
      at: now.toISOString(),
      body: body.slice(0, 200),
    }
  } catch (e) {
    // 超时 / 连接中断都归为「后端仍在启动」，而不是脚本出错
    const timedOut = e?.name === 'TimeoutError' || e?.name === 'AbortError'
    return {
      ok: false,
      status: 0,
      ms: Date.now() - startedAt,
      at: now.toISOString(),
      booting: timedOut,
      error: timedOut
        ? `后端仍在启动（${PROBE_TIMEOUT_MS}ms 内未响应），本次已触发唤醒，下一次定时任务会继续`
        : String(e),
    }
  }
}

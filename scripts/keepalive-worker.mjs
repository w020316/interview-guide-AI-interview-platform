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
 * 一起暂停到次月**。单个服务 7×24 常驻约 730h，仅剩约 20h 余量；若同时把
 * embedding 服务也保活（两个服务 ≈1460h）必然超额。
 * 因此本 Worker 默认只在北京时间 07:00–01:00 保活（约 18h/天 ≈ 540h/月，余量充足），
 * 且**只保活主后端**，embedding 服务按需唤醒（它只影响 RAG/AI 能力，不影响登录）。
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
 * 保活时间窗（北京时间，UTC+8）。
 * startHour=7、endHour=25 表示 07:00 → 次日 01:00。
 * 想 7×24 常驻就设 WARM_ALL_DAY = true —— 但请先确认当月 instance hours 有余量。
 */
const WARM_WINDOW_START_HOUR = 7
const WARM_WINDOW_END_HOUR = 25
const WARM_ALL_DAY = false

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

/** 当前是否处于保活时间窗内（北京时间） */
function inWarmWindow(now) {
  if (WARM_ALL_DAY) return true
  const cstHour = (now.getUTCHours() + 8) % 24
  const { start, end } = { start: WARM_WINDOW_START_HOUR, end: WARM_WINDOW_END_HOUR }
  if (end > 24) return cstHour >= start || cstHour < end - 24
  return cstHour >= start && cstHour < end
}

/**
 * 执行一次保活。
 *
 * 注意：实例冷启动可能长达数分钟（实测 338s / 355s，最慢超过 8 分钟也出现过），
 * 而 Worker 单次调用有墙钟上限，请求可能被中断 —— 这**不影响保活效果**：
 * 中断的请求同样已经触发了 Render 拉起实例，
 * 下一个 5 分钟周期的定时任务会命中一个正在启动/已就绪的实例。
 */
async function warm() {
  const now = new Date()
  const startedAt = Date.now()
  if (!inWarmWindow(now)) {
    return { skipped: true, reason: 'outside warm window', cstHour: (now.getUTCHours() + 8) % 24 }
  }
  const target = `${BACKEND}${PROBE_PATH}?_warm=${startedAt}`
  try {
    const res = await fetch(target, { method: 'GET', redirect: 'follow' })
    const body = await res.text()
    return {
      ok: res.ok,
      status: res.status,
      ms: Date.now() - startedAt,
      at: now.toISOString(),
      body: body.slice(0, 200),
    }
  } catch (e) {
    return {
      ok: false,
      status: 0,
      ms: Date.now() - startedAt,
      at: now.toISOString(),
      error: String(e),
    }
  }
}

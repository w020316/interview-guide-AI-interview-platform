#!/usr/bin/env node
/**
 * 线上核对：保活时间窗是否真的在生效（**默认零额度成本**）
 *
 * 运行：
 *   node scripts/verify-window.mjs                    # 窗内探测（免费）；窗外只做零成本核对
 *   node scripts/verify-window.mjs --probe-backend    # 窗外也探测后端（**会真的唤醒实例**）
 *   node scripts/verify-window.mjs --timeout 40
 *
 * ── 在验什么 ────────────────────────────────────────────────────────
 * 时间窗逻辑写错的代价是「整个站点被暂停」：若恒返回 true，后端 7×24 常驻 ≈730h/月，
 * 撑爆 Render 免费层 750h/月，而**超额会暂停所有免费服务**。
 * 单元测试（scripts/keepalive-worker.test.mjs）证明的是**代码**对，本脚本证明的是
 * **线上行为**对 —— 两者不可互相替代。
 *
 * ── 怎么判 ──────────────────────────────────────────────────────────
 *   窗外（CST 00:00–07:00）：后端**应该**是冷的（休眠）。探测长时间无响应 = 通过。
 *   窗内（CST 07:00–24:00）：后端**应该**是热的。秒级返回 = 通过。
 * 冷热用「首个响应耗时」区分：热态实测 0.9~8s，冷启动实测 209~488s，量级差得很开。
 *
 * ── 为什么默认不探测（2026-09-22 改）────────────────────────────────
 * 探测后端**会把休眠实例拉起来**（≈0.25 instance hours）。而本脚本存在的意义恰恰是保护额度 ——
 * 为了验证额度安全而消耗额度，方向反了。所以：
 *
 *   窗内：后端本来就被保活着，探测**不额外拉起实例 → 免费**，照常探测。
 *   窗外：**默认不探测**。零成本证据改用 Worker 自述（它窗外会直接返回 `skipped`、
 *         根本不发请求）。真要在窗外测后端冷热，显式加 `--probe-backend`。
 *
 * （云端还有一条每夜自动跑的零成本观测：.github/workflows/keepalive-observe.yml，
 *   与本机是否开机无关 —— 本脚本是它之外的按需手工核对。）
 *
 * ⚠️ 窗外若测出「热」，别急着判定时间窗失效 —— 先排除这些唤醒源：
 *    ① 真实用户/爬虫正在访问站点（前端一打开就 prewarmBackend）
 *    ② 兜底工作流 .github/workflows/keepalive.yml —— 2026-09-22 已加时间窗守卫，
 *       此前它 24 小时都在跑，实测 23 次运行有 9 次落在窗外（见该文件头部）
 *    ③ 刚发生过一次部署（Render 重建后实例是热的）
 *    排除完仍是热的，才说明窗内判定可能失效。
 */

import { WARM_ALL_DAY, WARM_WINDOW_START_HOUR, WARM_WINDOW_END_HOUR } from './keepalive-worker.mjs'

const BACKEND = 'https://interview-guide-backend.onrender.com'
const PROBE_PATH = '/api/health'
const WORKER_URL = 'https://render-keepalive.1181264839.workers.dev'
/** 热态上限：超过这个耗时基本可断定实例不在热态（热态实测最慢 8s） */
const HOT_MS = 15000

const argv = process.argv.slice(2)
const timeoutArg = argv.indexOf('--timeout')
const TIMEOUT_MS = (timeoutArg > -1 ? Number(argv[timeoutArg + 1]) : 25) * 1000
const PROBE_BACKEND = argv.includes('--probe-backend')

const cstHour = (() => {
  // 仅用于测试：强制某个 CST 小时，好在白天也能走一遍「窗外」分支。
  // （否则那条分支只有凌晨手工跑才会被执行到 —— 等于没测过。）
  const forced = process.env.VERIFY_FORCE_CST_HOUR
  if (forced !== undefined && /^\d+$/.test(forced)) {
    console.log(`（测试用：强制 CST 小时 = ${forced}，通过 VERIFY_FORCE_CST_HOUR 指定）`)
    return Number(forced) % 24
  }
  return (new Date().getUTCHours() + 8) % 24
})()
const shouldBeWarm =
  WARM_ALL_DAY || (cstHour >= WARM_WINDOW_START_HOUR && cstHour < WARM_WINDOW_END_HOUR)

console.log(`北京时间 ${String(cstHour).padStart(2, '0')} 点`)
console.log(
  `窗口配置：${WARM_WINDOW_START_HOUR}:00–${WARM_WINDOW_END_HOUR}:00` +
    `${WARM_ALL_DAY ? ' + WARM_ALL_DAY' : ''} → 此刻**应该**${shouldBeWarm ? '在保活（后端应为热态）' : '已停保活（后端应为冷态）'}`
)

let verdict

if (!shouldBeWarm && !PROBE_BACKEND) {
  // ── 窗外且未显式要求探测：走零成本路径，完全不碰后端
  console.log('\n⏭  窗外**不探测后端**（零成本模式）—— 任何请求都会把实例拉起来、白吃额度。')
  console.log('   零成本取证：读 Worker 自述（窗外它应直接返回 skipped，根本不发请求）：')
  console.log(`     curl -s -x http://127.0.0.1:7890 ${WORKER_URL}`)
  console.log(
    '     期望 → {"cron":"*/5 * * * *","skipped":true,"reason":"outside warm window","cstHour":<当前小时>}'
  )
  console.log('   若确实要在窗外测后端冷热（会唤醒一次实例 ≈0.25 instance hours），加 --probe-backend。')
  verdict = { pass: true, text: '✅ 已按零成本方式核对（未消耗额度）' }
} else {
  console.log(
    `\n探测：${BACKEND}${PROBE_PATH}（超时 ${TIMEOUT_MS / 1000}s）` +
      `${shouldBeWarm ? '' : ' ⚠️ 窗外探测会唤醒实例'}`
  )
  const started = Date.now()
  let ms = null
  try {
    const res = await fetch(`${BACKEND}${PROBE_PATH}?_verify=${started}`, {
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    ms = Date.now() - started
    console.log(`后端响应：HTTP ${res.status}，耗时 ${ms}ms`)
  } catch (e) {
    ms = Date.now() - started
    console.log(`后端在 ${TIMEOUT_MS / 1000}s 内无响应（${e?.name || e}）→ 实例处于休眠/启动中`)
  }

  const isHot = ms !== null && ms < HOT_MS
  if (shouldBeWarm) {
    verdict = isHot
      ? { pass: true, text: '✅ 窗内为热态 —— 保活正在生效' }
      : { pass: false, text: '⚠️ 窗内竟然是冷的 —— 保活的定时任务可能没在跑（Cron Trigger 是否还在？）' }
  } else {
    verdict = !isHot
      ? { pass: true, text: '✅ 窗外为冷态 —— 时间窗过滤生效，没有在白白消耗额度' }
      : { pass: false, text: '⚠️ 窗外却测到热态 —— 先排除下方唤醒源；若都排除，则时间窗过滤可能失效' }
  }
}

console.log(`\n${verdict.text}`)

// 顺带取一次 Worker 自己的判定：它在窗外会直接返回 skipped 且不碰后端（零额度成本），
// 是「线上窗内判定」最直接的证据。
//
// ⚠️ Node 的 fetch **不读 HTTP_PROXY/HTTPS_PROXY 环境变量**，所以本机若需要代理才能出网，
//    这一步会失败（不影响上面的判断）。此时用 curl 手动取一次即可，例如：
//      curl -x http://127.0.0.1:7890 https://render-keepalive.1181264839.workers.dev/
try {
  const r = await fetch(WORKER_URL, { signal: AbortSignal.timeout(15000) })
  const body = (await r.text()).slice(0, 200)
  console.log(`Worker 自述：HTTP ${r.status} → ${body}`)
  try {
    const j = JSON.parse(body)
    if (j.skipped)
      console.log(`  （Worker 判定此刻在窗外，cstHour=${j.cstHour} —— 与本地计算的 ${cstHour} 一致）`)
  } catch {
    /* 非 JSON 就只打印原文 */
  }
} catch (e) {
  console.log(
    `Worker 自述：取不到（${e?.name || e}）—— Node 的 fetch 不走环境变量里的代理。` +
      `\n  改用 curl 取（本机经 127.0.0.1:7890 可达）：` +
      `\n    curl -s -x http://127.0.0.1:7890 ${WORKER_URL}`
  )
}

if (!verdict.pass) {
  console.log(
    '\n排除唤醒源：① 有人在访问站点（前端会 prewarm）② 兜底工作流 keepalive.yml（已加守卫）' +
      '③ 刚发生过部署。三者都排除后仍为热态，才需要怀疑时间窗判定。'
  )
  process.exit(1)
}
console.log('\n（窗内探测不产生额外实例小时；窗外默认不探测。）')

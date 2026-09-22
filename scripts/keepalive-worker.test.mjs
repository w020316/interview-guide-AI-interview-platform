/**
 * 保活 Worker 的单元测试（Node 内置测试运行器，无需额外依赖）
 *
 * 运行：node --test scripts/keepalive-worker.test.mjs
 *
 * ── 为什么这几条测试值得存在 ─────────────────────────────────────────
 * 时间窗过滤逻辑写在 JS 里，一旦写错（尤其是**恒返回 true**），后端会 7×24 常驻
 * ≈730h/月，而 Render 免费层每 workspace 只有 750h —— **超额会暂停所有免费服务**。
 * 也就是说这段小逻辑的 bug 代价是「整个站点被暂停」，必须有测试兜住。
 *
 * ── 测试分两层，第二层才是真正的闸门（2026-09-22 补）─────────────────
 * ① **判定函数层**：直接测 `inWarmWindow()` 这个纯函数。
 * ② **闸门行为层**：冻结时钟 + 桩掉网络，驱动真实的 `worker.fetch()`
 *    （cron 与手动访问共用同一条代码路径），断言窗外**一次网络请求都不发**。
 *
 * 为什么必须有 ②：只测 ① 会漏掉「**调用点**被改坏」这条路。实测过——
 * 把 `warm()` 里的 `inWarmWindow(now)` 改成 `inWarmWindow(now, 0, 24)`
 * （生产等效恒为 true、后端 7×24 常驻），①②中的「判定函数层」测试**全部照常通过**，
 * 因为那些用例都在用一个不涉及调用点的默认参数。②能抓住它：它看的是
 * 「窗外到底有没有发请求」，而不是「函数返回了什么」。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import worker, {
  inWarmWindow,
  warmHoursPerDay,
  effectiveWarmHoursPerDay,
  ACTIVE_CRON,
  WARM_ALL_DAY,
} from './keepalive-worker.mjs'

/** 构造一个「北京时间 cstHour 点 minute 分」的时刻（CST = UTC+8） */
function atCst(cstHour, minute = 0) {
  const utcHour = (cstHour - 8 + 24) % 24
  return new Date(Date.UTC(2026, 8, 22, utcHour, minute))
}

/**
 * 在「冻结时钟 + 网络桩」下驱动一次真实的 Worker 入口。
 * 返回该次调用实际发出的探测 URL 列表与响应体 —— 这是**行为**证据，
 * 而不是「函数应该返回什么」的推理。
 */
async function runWorkerAt(cstHour, t, minute = 0) {
  t.mock.timers.enable({ apis: ['Date'], now: atCst(cstHour, minute).getTime() })
  const calls = []
  const realFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    calls.push(String(url))
    return new Response('{"status":"UP"}', { status: 200 })
  }
  try {
    const res = await worker.fetch()
    return { calls, body: await res.json() }
  } finally {
    t.mock.timers.reset()
    globalThis.fetch = realFetch
  }
}

// ───────────────────────── 第一层：判定函数 ─────────────────────────

test('默认窗口 07:00–24:00：CST 全天 24 小时的判定都正确', () => {
  for (let cstHour = 0; cstHour < 24; cstHour++) {
    const expected = cstHour >= 7 && cstHour < 24
    assert.equal(
      inWarmWindow(atCst(cstHour, 30)),
      expected,
      `CST ${cstHour}:30 判定错误（期望 ${expected}）`
    )
  }
})

test('默认窗口边界：06:59 在外、07:00 在内、23:59 在内、00:00 在外', () => {
  assert.equal(inWarmWindow(atCst(6, 59)), false, '06:59 应在窗外')
  assert.equal(inWarmWindow(atCst(7, 0)), true, '07:00 应在窗内')
  assert.equal(inWarmWindow(atCst(23, 59)), true, '23:59 应在窗内')
  assert.equal(inWarmWindow(atCst(0, 0)), false, '00:00 应在窗外')
})

test('跨天窗口（07:00 → 次日 01:00，end=25）判定正确', () => {
  const inWin = (cstHour) => inWarmWindow(atCst(cstHour), 7, 25, false)
  for (const h of [7, 12, 23, 0]) {
    assert.equal(inWin(h), true, `CST ${h} 应在跨天窗口内`)
  }
  for (const h of [1, 3, 6]) {
    assert.equal(inWin(h), false, `CST ${h} 应在跨天窗口外`)
  }
  assert.equal(warmHoursPerDay(7, 25), 18, '跨天窗口的每日小时数应算对')
})

test('WARM_ALL_DAY=true 时恒为窗内（这是有意保留的开关，代价是 ≈730h/月）', () => {
  for (let cstHour = 0; cstHour < 24; cstHour++) {
    assert.equal(inWarmWindow(atCst(cstHour), 7, 24, true), true)
  }
})

// ───────────────────────── 额度守卫 ─────────────────────────

test('生产开关：WARM_ALL_DAY 必须为 false', () => {
  assert.equal(
    WARM_ALL_DAY,
    false,
    'WARM_ALL_DAY=true 会让后端 7×24 常驻 ≈730h/月，撑爆 Render 免费层 750h/月，' +
      '**超额会暂停所有免费服务（不只是被保活的那个）**。如确需 7×24，' +
      '请先确认当月实际用量并同步更新本断言与 docs/keepalive-setup.md。'
  )
})

test('额度守卫：每日**实际**保活小时数必须在 8~20 之间', () => {
  const hours = effectiveWarmHoursPerDay()
  const monthly = Math.round(hours * 30.44)
  assert.ok(
    hours >= 8,
    `窗口过窄（${hours}h/天）：保活形同虚设，用户会重新吃到 3~8 分钟的冷启动 ` +
      `（这正是 2026-09-22 那次「登录等满 150s 失败」的根因）。`
  )
  assert.ok(
    hours <= 20,
    `窗口过宽（${hours}h/天 ≈ ${monthly}h/月）：会挤占 embedding 服务的额度，` +
      `逼近 Render 免费层 750h/月 的上限。超额会暂停所有免费服务，` +
      `如确需加宽请同时复核当月用量并更新本断言。`
  )
})

test('额度守卫必须对退化配置敏感：恒真（7×24）与恒假（无保活）都要被拦住', () => {
  const limit = 20
  const floor = 8
  // 恒真：开关打开 → 24h/天。守卫若不敏感，就是「形同虚设」，这段逻辑的 bug 会静默上线。
  assert.ok(
    effectiveWarmHoursPerDay(7, 24, true) > limit,
    '守卫对「恒真」不敏感 —— 它无法阻止 7×24 常驻'
  )
  // 恒假：start === end → 0h/天，保活彻底失效。
  assert.ok(
    effectiveWarmHoursPerDay(7, 7, false) < floor,
    '守卫对「恒假」不敏感 —— 它无法发现保活已失效'
  )
})

test('定时表达式为每 5 分钟，且小于 Render 的 15 分钟休眠阈值', () => {
  assert.equal(ACTIVE_CRON, '*/5 * * * *')
  const m = ACTIVE_CRON.match(/^\*\/(\d+) \* \* \* \*$/)
  assert.ok(m, `定时表达式格式异常：${ACTIVE_CRON}`)
  const intervalMinutes = Number(m[1])
  assert.ok(intervalMinutes > 0, '间隔必须为正')
  assert.ok(
    intervalMinutes < 15,
    `间隔 ${intervalMinutes} 分钟必须小于 Render 的 15 分钟休眠阈值，否则保活无效`
  )
})

// ─────────────── 第二层：闸门行为（冻结时钟 + 桩网络，走真实调用点）───────────────

test('闸门行为：窗外 CST 03:00 触发时，一次网络请求都不得发出', async (t) => {
  const { calls, body } = await runWorkerAt(3, t)
  assert.equal(
    calls.length,
    0,
    `窗外仍向后端发出了 ${calls.length} 次探测（${calls[0] || ''}）→ ` +
      `时间窗过滤失效，后端将 7×24 常驻 ≈730h/月，可能撑爆额度导致全站被暂停`
  )
  assert.equal(body.skipped, true, '窗外应明确返回 skipped')
  assert.equal(body.cstHour, 3, '窗外响应里的 cstHour 应为 3（便于线上核对）')
})

test('闸门行为：窗内 CST 12:00 触发时，恰好发出一次探测且指向健康检查端点', async (t) => {
  const { calls, body } = await runWorkerAt(12, t)
  assert.equal(calls.length, 1, `窗内应恰好探测一次，实际 ${calls.length} 次`)
  assert.match(calls[0], /^https:\/\/[^/]+\/api\/health\?_warm=\d+$/, '探测地址或防缓存参数不对')
  assert.equal(body.ok, true, '窗内探测应报告成功')
})

test('闸门行为：边界时刻的取舍正确（06:59 不探测 / 07:00 探测 / 23:59 探测 / 00:00 不探测）', async (t) => {
  const cases = [
    { hour: 6, minute: 59, expectCalls: 0, why: '06:59 仍在窗外，提前一小时开始保活会白吃额度' },
    { hour: 7, minute: 0, expectCalls: 1, why: '07:00 是窗口起点，必须开始保活' },
    { hour: 23, minute: 59, expectCalls: 1, why: '23:59 仍在窗口内，最后一刻不应提前停' },
    { hour: 0, minute: 0, expectCalls: 0, why: '00:00 已出窗口，必须停止保活' },
  ]
  for (const c of cases) {
    const { calls } = await runWorkerAt(c.hour, t, c.minute)
    assert.equal(
      calls.length,
      c.expectCalls,
      `CST ${c.hour}:${String(c.minute).padStart(2, '0')} 探测 ${calls.length} 次、期望 ${c.expectCalls} 次 —— ${c.why}`
    )
  }
})

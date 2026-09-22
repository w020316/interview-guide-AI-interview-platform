/**
 * 保活 Worker 的单元测试（Node 内置测试运行器，无需额外依赖）
 *
 * 运行：node --test scripts/keepalive-worker.test.mjs
 *
 * ── 为什么这几条测试值得存在 ─────────────────────────────────────────
 * 时间窗过滤逻辑写在 JS 里，一旦写错（尤其是**恒返回 true**），后端会 7×24 常驻
 * ≈730h/月，而 Render 免费层每 workspace 只有 750h —— **超额会暂停所有免费服务**。
 * 也就是说这段小逻辑的 bug 代价是「整个站点被暂停」，必须有测试兜住。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inWarmWindow, warmHoursPerDay, ACTIVE_CRON } from './keepalive-worker.mjs'

/** 构造一个「北京时间 cstHour 点整」的时刻（CST = UTC+8） */
function atCst(cstHour, minute = 0) {
  const utcHour = (cstHour - 8 + 24) % 24
  return new Date(Date.UTC(2026, 8, 22, utcHour, minute))
}

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

test('WARM_ALL_DAY=true 时恒为窗内（这是有意开关，代价是 ≈730h/月）', () => {
  for (let cstHour = 0; cstHour < 24; cstHour++) {
    assert.equal(inWarmWindow(atCst(cstHour), 7, 24, true), true)
  }
})

test('额度守卫：每日保活小时数必须在 1~20 之间', () => {
  const hours = warmHoursPerDay()
  const monthly = Math.round(hours * 30.44)
  assert.ok(hours >= 1, `窗口过窄（${hours}h/天），保活几乎无意义`)
  assert.ok(
    hours <= 20,
    `窗口过宽（${hours}h/天 ≈ ${monthly}h/月）：会挤占 embedding 服务的额度，` +
      `逼近 Render 免费层 750h/月 的上限。超额会暂停所有免费服务，` +
      `如确需加宽请同时复核当月用量并更新本断言。`
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

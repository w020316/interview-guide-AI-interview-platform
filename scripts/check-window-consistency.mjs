/**
 * 保活时间窗「一致性」检查（无第三方依赖，node 直接跑）
 *
 * 运行：node scripts/check-window-consistency.mjs
 *
 * ── 为什么需要它 ─────────────────────────────────────────────────────
 * 窗口策略有**三处实现**，且都在「写错就会撑爆 Render 免费额度」的关键路径上：
 *
 *   ① Cloudflare Worker（主力）  scripts/keepalive-worker.mjs          → inWarmWindow()
 *   ② GitHub Actions 兜底        .github/workflows/keepalive.yml       → 时间窗守卫步骤
 *   ③ GitHub Actions 云端观测    .github/workflows/keepalive-observe.yml → 期望判定（CST_H >= 7）
 *
 * 三处一旦漂移（比如只改了 ① 把窗口收窄，② 仍是 7×24），额度会按**较宽的那一处**
 * 消耗：免费层 750 instance hours/月，7×24 常驻就要 ≈730h，**超额会暂停所有免费服务**。
 * 这类漂移不会让任何测试变红 —— 各自文件里的测试都只盯着自己那份。
 * ③ 漂移的后果不同但同样隐蔽：观测的期望与真实窗口不符，会**持续误报**（假失败/假通过）。
 *
 * 本脚本把三者放一起比，并额外检查顺序约束（守卫必须是第一步、后续步骤必须被它挡住）
 * 与观测的两个 cron 槽（必须一个窗外、一个窗内，否则「窗外应冷」那一支永远测不到）。
 * ⚠️ 改窗口时：三处都要同步，然后**必须重跑本脚本 + 单元测试**。
 */

import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { WARM_ALL_DAY, WARM_WINDOW_START_HOUR, WARM_WINDOW_END_HOUR } from './keepalive-worker.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const WORKFLOW = join(HERE, '..', '.github', 'workflows', 'keepalive.yml')

const problems = []
const notes = []

function check(ok, message) {
  if (!ok) problems.push(message)
  return ok
}

const yml = await readFile(WORKFLOW, 'utf8')

// ① 从 YAML 里取出守卫的小时判定式：`if (( CST_H >= 7 && CST_H < 24 )); then`
const guardMatch = yml.match(/CST_H\s*>=\s*(\d+)\s*&&\s*CST_H\s*<\s*(\d+)/)
check(guardMatch, '兜底工作流里找不到时间窗守卫的小时判定式（CST_H >= X && CST_H < Y）')

if (guardMatch) {
  const [, guardStart, guardEnd] = guardMatch.map(Number)
  check(
    guardStart === WARM_WINDOW_START_HOUR && guardEnd === WARM_WINDOW_END_HOUR,
    `两处窗口不一致：Worker 是 ${WARM_WINDOW_START_HOUR}:00–${WARM_WINDOW_END_HOUR}:00，` +
      `兜底工作流是 ${guardStart}:00–${guardEnd}:00 —— 额度会按较宽的一处消耗`
  )
  notes.push(`窗口一致：两处均为北京时间 ${guardStart}:00–${guardEnd}:00（${guardEnd - guardStart}h/天）`)
}

// ② 开关不能开
check(!WARM_ALL_DAY, 'WARM_ALL_DAY 被打开了 → 7×24 常驻 ≈730h/月，会撑爆 750h 额度')

// ③ 守卫必须是作业的第一步，且后续每个步骤都被它挡住
const guardIdx = yml.indexOf('时间窗守卫')
const pingIdx = yml.indexOf('name: Wake & ping backend')
check(guardIdx > -1, '找不到守卫步骤')
check(
  guardIdx > -1 && pingIdx > -1 && guardIdx < pingIdx,
  '守卫步骤必须排在「Wake & ping backend」之前 —— 否则请求已经发出去了，守卫形同虚设'
)
const gatedSteps = yml.match(/if: steps\.window\.outputs\.in_window == 'true'/g) || []
const pingSteps = yml.match(/^\s*- name: (Wake & ping backend|Liveness & API sanity check)/gm) || []
check(
  gatedSteps.length === pingSteps.length && pingSteps.length > 0,
  `有步骤没被守卫挡住：会向后端发请求的步骤共 ${pingSteps.length} 个，带 in_window 条件的只有 ${gatedSteps.length} 个`
)
check(
  yml.includes("echo \"in_window=false\" >> \"$GITHUB_OUTPUT\""),
  '守卫缺少「按窗外处理」的失败分支（解析失败时必须失败到「跳过」，而不是「照跑」）'
)

// ④ 云端观测工作流（keepalive-observe.yml）—— 这是窗口策略的**第三处**实现
const OBSERVE = join(HERE, '..', '.github', 'workflows', 'keepalive-observe.yml')
const observeYml = await readFile(OBSERVE, 'utf8')

const obsStart = observeYml.match(/CST_H\s*>=\s*(\d+)/)
check(obsStart, '云端观测工作流里找不到期望判定式（CST_H >= X）')
if (obsStart) {
  const start = Number(obsStart[1])
  if (WARM_WINDOW_END_HOUR === 24) {
    check(
      start === WARM_WINDOW_START_HOUR,
      `云端观测的期望与 Worker 窗口不一致：观测从 ${start} 点起算「窗内」，` +
        `而 Worker 窗口是 ${WARM_WINDOW_START_HOUR}:00–${WARM_WINDOW_END_HOUR}:00`
    )
    notes.push(`云端观测（keepalive-observe.yml）与 Worker 窗口一致：窗内自 ${start} 点起算`)
  } else {
    check(
      false,
      `Worker 窗口不再以 24:00 结束（end=${WARM_WINDOW_END_HOUR}），但 keepalive-observe.yml ` +
        `用的是「CST_H >= 起始小时」这种单边判定 —— 请同步改成双边判定`
    )
  }
}

// 两个 cron 槽必须一个落在窗外、一个落在窗内（打错字会导致「永远只观测窗内」这种静默失效）
const crons = [...observeYml.matchAll(/cron:\s*'([^']+)'/g)].map((m) => m[1])
check(crons.length >= 2, `云端观测应有至少两个 cron 槽（窗外 + 窗内各一），实际 ${crons.length} 个`)
const cstHourOf = (cron) => {
  const [min, hour] = cron.split(/\s+/)
  if (!/^\d+$/.test(hour)) return null
  return (Number(hour) + 8) % 24
}
const obsHours = crons.map(cstHourOf)
if (obsHours.every((h) => h !== null)) {
  check(
    obsHours.some((h) => h < WARM_WINDOW_START_HOUR),
    `云端观测的 cron 槽没有一个落在窗外（CST 小时：${obsHours.join(', ')}，窗口起点 ${WARM_WINDOW_START_HOUR}）` +
      `—— 那样就永远达不到「窗外应冷」这一支`
  )
  check(
    obsHours.some((h) => h >= WARM_WINDOW_START_HOUR),
    `云端观测的 cron 槽没有一个落在窗内（CST 小时：${obsHours.join(', ')}）`
  )
  notes.push(`云端观测 cron 槽 → CST ${obsHours.map((h) => `${String(h).padStart(2, '0')} 点`).join('、')}（${crons.join(' / ')}）`)
}

if (problems.length > 0) {
  console.error('❌ 时间窗一致性检查未通过：\n')
  for (const p of problems) console.error(`  · ${p}`)
  console.error(
    '\n窗口策略的几处实现见：scripts/keepalive-worker.mjs、.github/workflows/keepalive.yml、' +
      '.github/workflows/keepalive-observe.yml'
  )
  process.exit(1)
}

console.log('✅ 时间窗一致性检查通过')
for (const n of notes) console.log(`  · ${n}`)
console.log(`  · 兜底工作流：${pingSteps.length} 个会发请求的步骤全部挂在守卫之后`)
console.log('  · 守卫失败方向：解析不出小时数时按「窗外」跳过（宁可漏一次兜底，也不白吃额度）')

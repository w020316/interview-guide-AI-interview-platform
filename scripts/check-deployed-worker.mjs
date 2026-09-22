#!/usr/bin/env node
/**
 * 核对**线上部署的** Worker 脚本与已注册的定时，与仓库里的文件是否一致
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * 单元测试证明的是「仓库里的代码对」，`verify-window.mjs` 证明的是「线上行为对」。
 * 但还有第三种情况两者都覆盖不到：**线上的脚本内容本身和仓库不一样** ——
 * 比如有人直接在 Cloudflare Dashboard 里手改过（去掉时间窗、把窗口改成 7×24）。
 * 那时线上行为可能正好对得上「当前时刻应该怎样」，却会在别的时间段失控。
 *
 * 本脚本拉取线上脚本原文与已注册的定时表达式，抽出关键指纹与本地逐项比对。
 *
 * ── 用法 ────────────────────────────────────────────────────────────
 *   # 走 Cloudflare API（需要一个只读 token，见下）
 *   CF_API_TOKEN=xxx node scripts/check-deployed-worker.mjs
 *
 *   # 离线：与一份导出的脚本文件比对（API 形状变动时的兜底，也可用于评审线上原文）
 *   node scripts/check-deployed-worker.mjs --source-file ./deployed-worker.mjs
 *
 * ── token 的最小权限 ────────────────────────────────────────────────
 *   Account → Workers Scripts → **Read**
 *   （只读即可，本脚本不写入任何东西；用完可到 Dashboard 吊销）
 *   可选环境变量：CF_ACCOUNT_ID（省略则取 token 可见的第一个账号）、
 *                 CF_WORKER_NAME（默认 render-keepalive）
 */

import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const API = 'https://api.cloudflare.com/client/v4'
const HERE = dirname(fileURLToPath(import.meta.url))
const LOCAL_SOURCE = join(HERE, 'keepalive-worker.mjs')

const TOKEN = process.env.CF_API_TOKEN
const WORKER_NAME = process.env.CF_WORKER_NAME || 'render-keepalive'
const fileArg = process.argv.indexOf('--source-file')
const SOURCE_FILE = fileArg > -1 ? process.argv[fileArg + 1] : null

/** 需要逐项比对的指纹字段（都是「写错就会撑爆额度」的关键配置） */
const FIELDS = [
  ['windowStart', '保活窗口起始小时', 'WARM_WINDOW_START_HOUR'],
  ['windowEnd', '保活窗口结束小时', 'WARM_WINDOW_END_HOUR'],
  ['allDay', '7×24 开关', 'WARM_ALL_DAY'],
  ['cron', '定时表达式', 'CRON_EXPRESSION'],
  ['probePath', '探测端点', 'PROBE_PATH'],
  ['probeTimeoutMs', '子请求超时（ms）', 'PROBE_TIMEOUT_MS'],
  ['backend', '后端地址', 'BACKEND'],
]

/**
 * 从一份 Worker 源码里抽出指纹（纯函数，便于离线测试）。
 *
 * 除常量外还检查**闸门顺序**：`if (!inWarmWindow(...))` 必须出现在所有
 * `await fetch(` 之前 —— 顺序反了就等于没有时间窗，而常量看起来完全正常。
 */
export function extractFingerprint(source) {
  const num = (name) => {
    const m = source.match(new RegExp(`const ${name}\\s*=\\s*(-?\\d+)`))
    return m ? Number(m[1]) : null
  }
  const str = (name) => {
    const m = source.match(new RegExp(`const ${name}\\s*=\\s*'([^']*)'`))
    return m ? m[1] : null
  }
  const bool = (name) => {
    const m = source.match(new RegExp(`const ${name}\\s*=\\s*(true|false)`))
    return m ? m[1] === 'true' : null
  }

  const gateAt = source.lastIndexOf('if (!inWarmWindow(')
  const netCalls = [...source.matchAll(/await fetch\(/g)].map((m) => m.index)
  const gateBeforeAllNetCalls = gateAt !== -1 && netCalls.length > 0 && netCalls.every((i) => i > gateAt)

  return {
    windowStart: num('WARM_WINDOW_START_HOUR'),
    windowEnd: num('WARM_WINDOW_END_HOUR'),
    allDay: bool('WARM_ALL_DAY'),
    cron: str('CRON_EXPRESSION'),
    probePath: str('PROBE_PATH'),
    probeTimeoutMs: num('PROBE_TIMEOUT_MS'),
    backend: str('BACKEND'),
    gateAt,
    netCallCount: netCalls.length,
    gateBeforeAllNetCalls,
  }
}

/** 比对两份指纹（纯函数）。返回差异列表，空数组表示一致。 */
export function diffFingerprints(local, deployed) {
  const diffs = []
  for (const [key, label] of FIELDS.map((f) => [f[0], f[1]])) {
    if (local[key] !== deployed[key]) {
      diffs.push(`${label} 不一致：仓库 ${JSON.stringify(local[key])} ←→ 线上 ${JSON.stringify(deployed[key])}`)
    }
  }
  if (!deployed.gateBeforeAllNetCalls) {
    diffs.push(
      `时间窗闸门顺序异常：线上脚本里有 ${deployed.netCallCount} 处 await fetch(，` +
        `但闸门（if (!inWarmWindow(）不在它们之前 —— 等价于没有时间窗`
    )
  }
  if (deployed.windowStart === null || deployed.windowEnd === null) {
    diffs.push('线上脚本里读不到窗口常量（可能被改写或已被 Dashboard 换成另一种写法）')
  }
  return diffs
}

/** 统一的 API 调用（与 deploy-keepalive-worker.mjs 同风格，失败时打印 CF 的 errors 明细） */
async function cf(path, { raw = false, accept } = {}) {
  const res = await fetch(`${API}${path}`, {
    headers: { Authorization: `Bearer ${TOKEN}`, ...(accept ? { Accept: accept } : {}) },
  })
  const text = await res.text()
  if (raw) {
    if (!res.ok) throw new Error(`调用 ${path} 失败（HTTP ${res.status}）：${text.slice(0, 200)}`)
    return { text, contentType: res.headers.get('content-type') || '' }
  }
  let json
  try {
    json = JSON.parse(text)
  } catch {
    throw new Error(`Cloudflare 返回非 JSON（HTTP ${res.status}）：${text.slice(0, 300)}`)
  }
  if (!json.success) {
    const detail = (json.errors || []).map((e) => `[${e.code}] ${e.message}`).join('; ')
    const hint =
      res.status === 403 || (json.errors || []).some((e) => e.code === 10000)
        ? '\n→ 多半是 token 权限不足：需要 Account → Workers Scripts → Read'
        : ''
    throw new Error(`调用 ${path} 失败（HTTP ${res.status}）：${detail || text.slice(0, 200)}${hint}`)
  }
  return json.result
}

async function resolveAccountId() {
  if (process.env.CF_ACCOUNT_ID) return process.env.CF_ACCOUNT_ID
  const accounts = await cf('/accounts')
  if (!Array.isArray(accounts) || accounts.length === 0) {
    throw new Error('token 下没有可见账号，请显式设置 CF_ACCOUNT_ID')
  }
  return accounts[0].id
}

/**
 * 下载线上脚本原文。
 * Cloudflare 对单模块（本项目用 main_module 上传）的下载端点是 .../content；
 * 多模块会返回 multipart，这里取出其中最长的一段作为脚本正文。
 * ⚠️ 本函数的 API 形状**未经实机验证**（开发环境到不了 CF 且无 token）——
 *    因此提供 --source-file 兜底：也可从 Dashboard 复制线上脚本比对。
 */
async function downloadDeployedSource(accountId) {
  const candidates = [
    `/accounts/${accountId}/workers/scripts/${WORKER_NAME}/content/v2`,
    `/accounts/${accountId}/workers/scripts/${WORKER_NAME}/content`,
  ]
  const errors = []
  for (const path of candidates) {
    try {
      const { text, contentType } = await cf(path, { raw: true })
      if (text.includes('WARM_WINDOW_START_HOUR')) return { text, via: path }
      if (contentType.includes('multipart')) {
        const parts = text.split(/\r?\n\r?\n/)
        const biggest = parts.reduce((a, b) => (b.length > a.length ? b : a), '')
        if (biggest.includes('WARM_WINDOW_START_HOUR')) {
          return { text: biggest, via: `${path}（multipart 中最长的一段）` }
        }
      }
      errors.push(`${path}：拿到了内容但里面没有窗口常量（${text.length} 字节）`)
    } catch (e) {
      errors.push(`${path}：${e.message.split('\n')[0]}`)
    }
  }
  throw new Error(`无法取得线上脚本原文：\n    - ${errors.join('\n    - ')}`)
}

async function registeredCrons(accountId) {
  const result = await cf(`/accounts/${accountId}/workers/scripts/${WORKER_NAME}/schedules`)
  return (result?.schedules || []).map((s) => s.cron)
}

async function main() {
  const localSource = await readFile(LOCAL_SOURCE, 'utf8')
  const local = extractFingerprint(localSource)

  let deployedSource
  let via
  let crons = null

  if (SOURCE_FILE) {
    deployedSource = await readFile(SOURCE_FILE, 'utf8')
    via = `本地文件 ${SOURCE_FILE}`
  } else {
    if (!TOKEN) {
      console.error(
        '缺少 CF_API_TOKEN。两种用法：\n' +
          '  CF_API_TOKEN=xxx node scripts/check-deployed-worker.mjs   # token 只需 Account → Workers Scripts → Read\n' +
          '  node scripts/check-deployed-worker.mjs --source-file <从 Dashboard 导出的脚本文件>\n'
      )
      process.exit(2)
    }
    const accountId = await resolveAccountId()
    console.log(`Worker：${WORKER_NAME}    账号：${accountId}\n`)
    const r = await downloadDeployedSource(accountId)
    deployedSource = r.text
    via = r.via
    crons = await registeredCrons(accountId)
  }

  const deployed = extractFingerprint(deployedSource)

  console.log(`线上脚本来源：${via}`)
  console.log(`线上已注册定时：${crons ? crons.join(', ') || '(空 —— 保活根本不会触发！)' : '（未查询，离线模式）'}\n`)

  console.log('字段'.padEnd(20) + '仓库'.padEnd(28) + '线上')
  for (const [key, label] of FIELDS.map((f) => [f[0], f[1]])) {
    console.log(`${label.padEnd(18)}  ${JSON.stringify(local[key]).padEnd(26)} ${JSON.stringify(deployed[key])}`)
  }
  console.log(
    `\n闸门顺序：仓库 ${local.gateBeforeAllNetCalls ? '✅ 闸门在所有 fetch 之前' : '❌ 异常'}  ` +
      `线上 ${deployed.gateBeforeAllNetCalls ? '✅ 闸门在所有 fetch 之前' : '❌ 异常'}`
  )

  const problems = diffFingerprints(local, deployed)
  if (crons) {
    if (crons.length === 0) problems.push('线上没有注册任何 Cron Trigger —— 保活不会触发（定时在 Cloudflare 侧，不在脚本里）')
    else if (!crons.includes(local.cron)) {
      problems.push(`已注册定时与代码不一致：注册的是 ${crons.join(', ')}，代码里写的是 ${local.cron}`)
    }
  }

  console.log()
  if (problems.length > 0) {
    console.error('❌ 线上与仓库不一致：\n')
    for (const p of problems) console.error(`  · ${p}`)
    console.error('\n→ 用 scripts/deploy-keepalive-worker.mjs 重新上传可同步（需要 Workers Scripts → Edit 权限）。')
    process.exit(1)
  }
  console.log('✅ 线上脚本与仓库一致，已注册定时也匹配')
  console.log('   （本脚本只比对「配置与结构」；线上**行为**是否随时间窗变化，用 scripts/verify-window.mjs 取证。）')
}

// 仅在直接执行时跑 main；被测试 import 时不执行
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((e) => {
    console.error(`\n检查失败：${e.message}`)
    process.exit(1)
  })
}

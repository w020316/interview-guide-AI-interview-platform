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
 *   # 离线：与一份导出的脚本文件比对
 *   #   —— 文件可以是「纯脚本」，也可以是 CF 下载端点的**原始 multipart 响应**
 *   #      （会先剥包装再比对），后者便于把线上原文整份留档后再核
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

/**
 * 从响应体里取出脚本正文。
 *
 * Cloudflare 对单模块脚本的下载端点返回的是 **multipart/form-data**（不是裸 JS）：
 * 2026-09-22 实测的真实响应（4679 字符）形如
 *
 *   --<boundary>\r\n
 *   Content-Disposition: form-data; name="keepalive-worker.mjs"; filename="keepalive-worker.mjs"\r\n
 *   Content-Type: application/javascript+module\r\n
 *   \r\n
 *   <脚本正文 6891 字节 / 4406 字符>\r\n
 *   --<boundary>--\r\n
 *
 * 所以必须剥掉包装再抽指纹；直接把整段丢给正则也能"凑巧"命中常量，但会让
 * 「闸门顺序」这类基于位置的判断失真。不是 multipart 就原样返回（`--source-file`
 * 传入纯脚本时走这条路）。
 */
export function extractModuleSource(text) {
  if (!text.startsWith('--')) return text
  const nl = text.includes('\r\n') ? '\r\n' : '\n'
  const boundary = text.slice(0, text.indexOf(nl)).trim()
  if (!/^--\S+$/.test(boundary)) return text
  const headEnd = text.indexOf(nl + nl)
  if (headEnd === -1) return text
  const closingAt = text.lastIndexOf(nl + boundary)
  return closingAt > headEnd
    ? text.slice(headEnd + nl.length * 2, closingAt)
    : text.slice(headEnd + nl.length * 2)
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
 *
 * ⚠️ 这段**HTTP 调用本身仍未实机验证**（本机没有 token，无法用 Bearer 走一遍），
 *    但返回体的**形状已按真实响应验证过**：2026-09-22 用已登录的浏览器会话在
 *    dash 页面内调同一端点，拿到的是 multipart，已据此实现 extractModuleSource，
 *    并与仓库 blob 做过逐字节比对（详见 docs/keepalive-setup.md 第 9 节）。
 *    若 Cloudflare 改了返回形状，用 `--source-file` 兜底：把响应原文或 Dashboard
 *    里复制的脚本存成文件传进来即可。
 */
async function downloadDeployedSource(accountId) {
  const candidates = [
    `/accounts/${accountId}/workers/scripts/${WORKER_NAME}/content/v2`,
    `/accounts/${accountId}/workers/scripts/${WORKER_NAME}/content`,
  ]
  const errors = []
  for (const path of candidates) {
    try {
      const { text } = await cf(path, { raw: true })
      const source = extractModuleSource(text)
      if (source.includes('WARM_WINDOW_START_HOUR')) {
        return { text: source, via: path + (source === text ? '' : '（已剥掉 multipart 包装）') }
      }
      errors.push(`${path}：拿到了内容但里面没有窗口常量（${text.length} 字符）`)
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
    const rawText = await readFile(SOURCE_FILE, 'utf8')
    deployedSource = extractModuleSource(rawText)
    via =
      `本地文件 ${SOURCE_FILE}` + (deployedSource === rawText ? '' : '（已剥掉 multipart 包装）')
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

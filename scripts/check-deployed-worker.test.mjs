/**
 * check-deployed-worker.mjs 的离线单元测试
 *
 * 运行：node --test scripts/check-deployed-worker.test.mjs
 *
 * ── 为什么这几条值得存在 ────────────────────────────────────────────
 * 「线上脚本 == 仓库脚本」的比对逻辑里，**只有比对部分是能离线测的**：
 * 拉取线上原文那一步依赖 Cloudflare API 与一个 token（本机到不了、也没有 token，
 * 所以那段代码未经实机验证，已在该文件注释里明确标注）。
 * 既然比对部分可以测，就必须测 —— 否则一个写错的比对会给出「✅ 一致」这种
 * 最危险的假绿灯。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { extractFingerprint, diffFingerprints, extractModuleSource } from './check-deployed-worker.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const LOCAL = await readFile(join(HERE, 'keepalive-worker.mjs'), 'utf8')

/**
 * 线上**实际部署**的那一版（提交 ed9bcf4）的关键声明。
 * 与仓库版本的差别：判定函数的 start/end 是在函数体内重新取常量的、
 * 且没有可覆盖的默认参数、也没有 export —— 指纹应当**完全一致**。
 */
const DEPLOYED_ED9BCF4 = `
const BACKEND = 'https://interview-guide-backend.onrender.com'
const CRON_EXPRESSION = '*/5 * * * *'
const PROBE_PATH = '/api/health'
const PROBE_TIMEOUT_MS = 25000
const WARM_WINDOW_START_HOUR = 7
const WARM_WINDOW_END_HOUR = 24
const WARM_ALL_DAY = false
function inWarmWindow(now) {
  if (WARM_ALL_DAY) return true
  const cstHour = (now.getUTCHours() + 8) % 24
  const { start, end } = { start: WARM_WINDOW_START_HOUR, end: WARM_WINDOW_END_HOUR }
  if (end > 24) return cstHour >= start || cstHour < end - 24
  return cstHour >= start && cstHour < end
}
async function warm() {
  const now = new Date()
  if (!inWarmWindow(now)) return { skipped: true }
  const res = await fetch('https://example.com', {})
  return res
}
`

test('能从仓库源码里抽全指纹', () => {
  const fp = extractFingerprint(LOCAL)
  assert.equal(fp.windowStart, 7)
  assert.equal(fp.windowEnd, 24)
  assert.equal(fp.allDay, false)
  assert.equal(fp.cron, '*/5 * * * *')
  assert.equal(fp.probePath, '/api/health')
  assert.equal(fp.probeTimeoutMs, 25000)
  assert.equal(fp.backend, 'https://interview-guide-backend.onrender.com')
  assert.equal(fp.netCallCount, 1, '模块里应当只有一处网络调用（探测）')
  assert.equal(fp.gateBeforeAllNetCalls, true, '闸门必须在唯一的网络调用之前')
})

test('线上实际部署版本（ed9bcf4）与仓库指纹一致 —— 这是「已测代码即线上行为」的证据', () => {
  const diffs = diffFingerprints(extractFingerprint(LOCAL), extractFingerprint(DEPLOYED_ED9BCF4))
  assert.deepEqual(diffs, [], `线上版本与仓库应当逐项一致，却报出：\n${diffs.join('\n')}`)
})

test('比对是自反的：同一份源码与自己比对必须零差异', () => {
  const fp = extractFingerprint(LOCAL)
  assert.deepEqual(diffFingerprints(fp, fp), [])
})

test('能抓住窗口被放宽的漂移（每项都必须单独报出来）', () => {
  const local = extractFingerprint(LOCAL)
  const cases = [
    ['WARM_WINDOW_END_HOUR = 25', 'WARM_WINDOW_END_HOUR = 24', '保活窗口结束小时'],
    ['WARM_ALL_DAY = true', 'WARM_ALL_DAY = false', '7×24 开关'],
    ["CRON_EXPRESSION = '*/1 * * * *'", "CRON_EXPRESSION = '*/5 * * * *'", '定时表达式'],
    ["PROBE_PATH = '/api/info'", "PROBE_PATH = '/api/health'", '探测端点'],
    ['PROBE_TIMEOUT_MS = 60000', 'PROBE_TIMEOUT_MS = 25000', '子请求超时'],
  ]
  for (const [from, to, label] of cases) {
    const drifted = LOCAL.replace(to, from)
    assert.notEqual(drifted, LOCAL, `fixture 没生效：${from}`)
    const diffs = diffFingerprints(local, extractFingerprint(drifted))
    assert.ok(
      diffs.some((d) => d.includes(label)),
      `把「${label}」改成 ${from} 后没被报出来（这会造成假绿灯）`
    )
  }
})

test('能抓住闸门顺序错误：闸门被摘掉后，常量再正常也必须报警', () => {
  const local = extractFingerprint(LOCAL)
  const noGate = LOCAL.replace('if (!inWarmWindow(now)) {\n    return { skipped: true, reason: \'outside warm window\', cstHour: (now.getUTCHours() + 8) % 24 }\n  }', '')
  const fp = extractFingerprint(noGate)
  assert.equal(fp.gateBeforeAllNetCalls, false, 'fixture 没生效：闸门应当已被摘掉')
  const diffs = diffFingerprints(local, fp)
  assert.ok(
    diffs.some((d) => d.includes('闸门顺序异常')),
    `闸门被摘掉却没报「闸门顺序异常」：${JSON.stringify(diffs)}`
  )
})

test('能抓住「常量读不到」这种更隐蔽的改写', () => {
  const local = extractFingerprint(LOCAL)
  const obfuscated = LOCAL.replace(/const WARM_WINDOW_START_HOUR\s*=\s*7/, 'const WARM_WINDOW_START_HOUR = Number(process.env.START_HOUR ?? 7)')
  const diffs = diffFingerprints(local, extractFingerprint(obfuscated))
  assert.ok(
    diffs.some((d) => d.includes('读不到窗口常量')),
    '常量被改成运行期取值却没报出来（线上窗口可能随环境变量漂移）'
  )
})

// ── multipart 剥壳：CF 的下载端点返回的不是裸 JS ──────────────────────────
// 形状取自 2026-09-22 的真实响应（含 CRLF、boundary 在首行、结尾 `--boundary--`）。

/** 按 CF 的真实形状把一段脚本包成 multipart */
function wrapMultipart(script, { boundary = 'f717e855f2e77228d2b2c33cb340ba90cd6a16accab0ab6358ee96c0836b', nl = '\r\n' } = {}) {
  return [
    `--${boundary}`,
    'Content-Disposition: form-data; name="keepalive-worker.mjs"; filename="keepalive-worker.mjs"',
    'Content-Type: application/javascript+module',
    '',
    script,
    `--${boundary}--`,
    '',
  ].join(nl)
}

test('剥壳：纯脚本原样返回，不做任何改动', () => {
  assert.equal(extractModuleSource(LOCAL), LOCAL)
})

test('剥壳：真实形状的 multipart（CRLF）能剥出正文，且抽出的指纹与纯脚本一致', () => {
  const wrapped = wrapMultipart(LOCAL)
  const unwrapped = extractModuleSource(wrapped)
  assert.ok(unwrapped.startsWith('/**'), `剥壳后应以脚本开头的注释起始，实际开头：${JSON.stringify(unwrapped.slice(0, 40))}`)
  assert.ok(!unwrapped.includes('Content-Disposition'), '剥壳后不该残留 multipart 头部')
  assert.ok(!unwrapped.includes('--f717e855'), '剥壳后不该残留 boundary')
  assert.deepEqual(
    diffFingerprints(extractFingerprint(LOCAL), extractFingerprint(unwrapped)),
    [],
    '包进 multipart 再剥出来，指纹必须与原始脚本完全一致（否则比对会失真）'
  )
})

test('剥壳：LF 行尾的 multipart 同样能剥（不同客户端/网关可能给出不同行尾）', () => {
  const unwrapped = extractModuleSource(wrapMultipart(LOCAL, { nl: '\n' }))
  assert.ok(unwrapped.startsWith('/**') && !unwrapped.includes('Content-Disposition'))
  assert.deepEqual(diffFingerprints(extractFingerprint(LOCAL), extractFingerprint(unwrapped)), [])
})

test('剥壳：不是 multipart 的畸形输入不应抛异常（宁可原样返回，交给比对去报错）', () => {
  assert.equal(extractModuleSource(''), '')
  assert.equal(extractModuleSource('--only-a-boundary-line'), '--only-a-boundary-line')
  assert.equal(extractModuleSource('const A = 1'), 'const A = 1')
})

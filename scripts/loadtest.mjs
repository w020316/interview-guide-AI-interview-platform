#!/usr/bin/env node
/**
 * 轻量并发压测脚本（v1.32.0）
 *
 * 用途：验证后端在并发访问下的稳定性与响应时间，用于上线前后回归。
 * 无第三方依赖（Node >= 18 原生 http）。
 *
 * 用法：
 *   node scripts/loadtest.mjs                        # 默认打生产 /api/health
 *   node scripts/loadtest.mjs --url https://host/api/info --concurrency 50 --requests 500
 *
 * 输出：总耗时、成功/失败数、QPS、P50/P95/P99 延迟、HTTP 状态码分布。
 */
import http from 'node:http'
import https from 'node:https'
import { URL } from 'node:url'

const args = process.argv.slice(2)
const parse = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const TARGET = parse('--url', 'https://interview-guide-backend.onrender.com/api/health')
const CONCURRENCY = parseInt(parse('--concurrency', '30'), 10)
const TOTAL = parseInt(parse('--requests', '300'), 10)
const TIMEOUT_MS = parseInt(parse('--timeout', '30000'), 10)

const { protocol, hostname, port, pathname, search } = new URL(TARGET)
const isHttps = protocol === 'https:'
const requestPort = port || (isHttps ? 443 : 80)

const latencies = []
let success = 0
let failed = 0
const statusCount = new Map()
let inflight = 0
let dispatched = 0
const startTime = Date.now()

function sendOne() {
  return new Promise((resolve) => {
    const begun = Date.now()
    const req = (isHttps ? https : http).request({
      hostname, port: requestPort, path: pathname + (search || ''), method: 'GET',
      headers: { 'User-Agent': 'loadtest/1.0', Connection: 'keep-alive' },
    }, (res) => {
      res.resume() // 丢弃 body
      res.on('end', () => {
        const ms = Date.now() - begun
        latencies.push(ms)
        statusCount.set(res.statusCode, (statusCount.get(res.statusCode) || 0) + 1)
        if (res.statusCode >= 200 && res.statusCode < 400) success++
        else failed++
        resolve()
      })
    })
    req.setTimeout(TIMEOUT_MS, () => {
      req.destroy(new Error('timeout'))
    })
    req.on('error', () => {
      latencies.push(TIMEOUT_MS)
      failed++
      statusCount.set('ERR', (statusCount.get('ERR') || 0) + 1)
      resolve()
    })
    req.end()
  })
}

async function worker() {
  while (dispatched < TOTAL) {
    dispatched++
    await sendOne()
  }
}

const percentile = (p) => {
  if (!latencies.length) return 0
  const sorted = [...latencies].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}

await Promise.all(Array.from({ length: CONCURRENCY }, worker))
const elapsed = (Date.now() - startTime) / 1000

console.log('\n========== 压测结果 ==========')
console.log(`目标     : ${TARGET}`)
console.log(`并发     : ${CONCURRENCY}  |  总请求: ${TOTAL}  |  超时: ${TIMEOUT_MS}ms`)
console.log(`总耗时   : ${elapsed.toFixed(2)}s`)
console.log(`成功     : ${success}  |  失败: ${failed}  |  成功率: ${((success / TOTAL) * 100).toFixed(2)}%`)
console.log(`QPS      : ${(TOTAL / elapsed).toFixed(1)}`)
console.log(`P50      : ${percentile(50)}ms  |  P95: ${percentile(95)}ms  |  P99: ${percentile(99)}ms`)
console.log(`状态码   : ${JSON.stringify(Object.fromEntries(statusCount))}`)
console.log('================================')

process.exit(failed > 0 ? 1 : 0)

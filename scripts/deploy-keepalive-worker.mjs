#!/usr/bin/env node
/**
 * 一键部署 Render 保活 Worker 到 Cloudflare（走官方 REST API，无需浏览器登录）
 *
 * ── 为什么需要这个脚本 ───────────────────────────────────────────────
 * 保活必须由**准点触发**的外部定时器完成（GitHub Actions 的 schedule 会被降级到
 * 3~6 小时一次，见 docs/keepalive-setup.md）。Cloudflare Workers 的 Cron Trigger
 * 免费且准点，本项目前端也已托管在 Cloudflare Pages，无需新增服务商。
 *
 * 手动在 Dashboard 点几下也能完成，但用脚本更省事、可重复执行（幂等覆盖同名 Worker）。
 *
 * ── 前置：创建一个最小权限的 API Token（不要用全局 API Key）──────────
 * 1. 打开 https://dash.cloudflare.com/profile/api-tokens
 * 2. Create Token → 选「Custom token」
 * 3. Permissions 只加一条：Account → Workers Scripts → Edit
 *    （本脚本还会读 accounts / workers/subdomain，若提示权限不足，
 *      再加一条 Account → Workers Scripts → Read 即可）
 * 4. Account Resources 选你的账号；TTL 建议设短一些（如 1 天），用完即删
 *
 * ── 用法 ────────────────────────────────────────────────────────────
 *   # Git Bash / macOS / Linux
 *   CF_API_TOKEN=xxxxx node scripts/deploy-keepalive-worker.mjs
 *
 *   # PowerShell
 *   $env:CF_API_TOKEN="xxxxx"; node scripts/deploy-keepalive-worker.mjs
 *
 * 可选环境变量：
 *   CF_ACCOUNT_ID    账号 ID（省略则自动取 token 下的第一个账号）
 *   CF_WORKER_NAME   Worker 名称，默认 render-keepalive
 *   CF_CRON          Cron 表达式，默认值见下方 DEFAULT_CRON（每 5 分钟一次）
 *
 * ── 安全提醒 ────────────────────────────────────────────────────────
 * 脚本**只从环境变量读取 token，绝不写入任何文件**。请勿把 token 提交进仓库。
 * 若 token 曾出现在聊天记录/日志里，用完请到 Dashboard 吊销它。
 */

import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const API = 'https://api.cloudflare.com/client/v4'
const HERE = dirname(fileURLToPath(import.meta.url))
const WORKER_SOURCE = join(HERE, 'keepalive-worker.mjs')

const TOKEN = process.env.CF_API_TOKEN
const WORKER_NAME = process.env.CF_WORKER_NAME || 'render-keepalive'
/** 默认 Cron：每 5 分钟一次（远小于 Render 的 15 分钟休眠阈值，留 3 倍余量） */
const DEFAULT_CRON = '*/5 * * * *'
const CRON = process.env.CF_CRON || DEFAULT_CRON
const COMPATIBILITY_DATE = '2026-09-01'

if (!TOKEN) {
  console.error('缺少 CF_API_TOKEN 环境变量。用法见本文件顶部注释。')
  process.exit(2)
}

/** 统一的 API 调用，失败时打印 Cloudflare 返回的 errors 明细 */
async function cf(path, { method = 'GET', body, headers = {}, raw = false } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...headers },
    body,
  })
  const text = await res.text()
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
        ? '\n→ 多半是 token 权限不足：需要 Account → Workers Scripts → Edit'
        : ''
    throw new Error(`调用 ${path} 失败（HTTP ${res.status}）：${detail || text.slice(0, 200)}${hint}`)
  }
  return raw ? json : json.result
}

/** 解析账号 ID：优先用环境变量，否则取 token 可见的第一个账号 */
async function resolveAccountId() {
  if (process.env.CF_ACCOUNT_ID) return process.env.CF_ACCOUNT_ID
  const accounts = await cf('/accounts')
  if (!Array.isArray(accounts) || accounts.length === 0) {
    throw new Error('token 下没有可见账号，请显式设置 CF_ACCOUNT_ID')
  }
  if (accounts.length > 1) {
    console.log(`token 可见 ${accounts.length} 个账号，使用第一个：${accounts[0].name}`)
  }
  return accounts[0].id
}

/**
 * 上传（并立即部署）Worker 模块。
 * multipart 约定见 https://developers.cloudflare.com/workers/configuration/multipart-upload-metadata/
 *   - metadata 部分：JSON，必须含 main_module
 *   - 模块文件部分：part 名必须等于 main_module 的值
 */
async function uploadWorker(accountId, code) {
  const form = new FormData()
  form.append(
    'metadata',
    new Blob([JSON.stringify({ main_module: 'keepalive-worker.mjs', compatibility_date: COMPATIBILITY_DATE })], {
      type: 'application/json',
    })
  )
  form.append(
    'keepalive-worker.mjs',
    new Blob([code], { type: 'application/javascript+module' }),
    'keepalive-worker.mjs'
  )
  await cf(`/accounts/${accountId}/workers/scripts/${WORKER_NAME}`, { method: 'PUT', body: form })
}

/** 设置 Cron Trigger；body 是数组，元素形如 {"cron": "..."} */
async function setCron(accountId) {
  const result = await cf(`/accounts/${accountId}/workers/scripts/${WORKER_NAME}/schedules`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify([{ cron: CRON }]),
  })
  return (result?.schedules || []).map((s) => s.cron)
}

/** 取 workers.dev 子域，便于给出可直接打开的验证地址 */
async function workersDevUrl(accountId) {
  try {
    const sub = await cf(`/accounts/${accountId}/workers/subdomain`)
    if (sub?.subdomain) return `https://${WORKER_NAME}.${sub.subdomain}.workers.dev`
  } catch {
    // 子域未开通或权限不足都不影响保活本身，忽略
  }
  return null
}

async function main() {
  const code = await readFile(WORKER_SOURCE, 'utf8')
  console.log(`Worker 名称：${WORKER_NAME}`)
  console.log(`Cron      ：${CRON}`)

  const accountId = await resolveAccountId()
  console.log(`账号 ID   ：${accountId}`)

  console.log('\n[1/3] 上传 Worker 脚本…')
  await uploadWorker(accountId, code)
  console.log('      已上传并部署')

  console.log('[2/3] 设置 Cron Trigger…')
  const crons = await setCron(accountId)
  console.log(`      已生效的定时表达式：${crons.join(', ') || '(空)'}`)

  console.log('[3/3] 解析验证地址…')
  const url = await workersDevUrl(accountId)
  if (url) {
    console.log(`      打开 ${url} 应返回 {"cron":"...","ok":true,"status":200,...}`)
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(60_000) })
      const body = (await r.text()).slice(0, 200)
      console.log(`      实测：HTTP ${r.status} → ${body}`)
    } catch (e) {
      console.log(`      实测失败（不影响保活，稍后由 Cron 自动重试）：${e.message}`)
    }
  } else {
    console.log('      未取到 workers.dev 子域，可在 Dashboard 的 Worker 详情页查看')
  }

  console.log('\n完成。保活时间窗与 Render 额度约束见 docs/keepalive-setup.md。')
}

main().catch((e) => {
  console.error(`\n部署失败：${e.message}`)
  process.exit(1)
})

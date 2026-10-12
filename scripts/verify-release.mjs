#!/usr/bin/env node
/**
 * 发布前「一条命令」验收 —— verify-release.mjs
 * ══════════════════════════════════════════════════════════════════════
 *
 * 为什么需要它
 * ────────────────────────────────────────────────────────────────────
 * `docs/发布清单.md` 第三节点了「vue-tsc / vitest / vite build」三项，
 * 但 2026-10-12 发 v1.66.20 时，交给验证方的任务说明里**漏掉了 `vite build`**。
 * 后果不是「少跑一个检查」，而是**排查成本翻倍**：
 * Cloudflare Pages 的部署依赖构建，构建若失败，线上表现就是
 * 「push 了但站点不动」—— 与「平台没触发构建」的现象**完全一样**，
 * 而当时没有人跑过构建，只能临时补跑才排除掉这条。
 *
 * 结论：清单靠人逐条记，迟早会漏；把清单固化成**一条命令**，漏就无从发生。
 *
 * 用法
 * ────────────────────────────────────────────────────────────────────
 *   node scripts/verify-release.mjs              # 本地三项回归
 *   node scripts/verify-release.mjs --live       # 额外验「线上是否真的部署了」
 *   node scripts/verify-release.mjs --live --url=https://xxx.pages.dev
 *
 * 本地三项（顺序固定，任一失败立即中止）
 *   1) npx vue-tsc --noEmit          类型检查
 *   2) npx vitest run                全量单测（含 designGuards / changelog 门禁）
 *   3) npx vite build                构建（**这一步是 CF Pages 部署的前提**）
 *
 * 关于第 3 步的一个环境坑
 *   AI 沙箱内直接 `vite build` 会失败：Vite 准备阶段要清空 `dist/assets`
 *   （>50 项）会触发沙箱的 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`，先清 dist 也没用。
 *   判据是「报错在准备阶段 + 日志含 safe-delete」⇒ **环境守卫，不是构建坏了**。
 *   本脚本会自动识别这个特征，并改用仓库外的临时目录重试一次，
 *   这样沙箱内外都能拿到「构建是否真的能过」的结论。
 *
 * --live 做什么（只读，不推任何东西）
 *   1) 从 `frontend/src/changelog.ts` 读 `CURRENT_VERSION`
 *   2) 无缓存拉线上 index.html（随机查询串 + no-cache，排除 CDN 假象）
 *   3) 取入口 chunk 名 → 抓下来搜 `CURRENT_VERSION`
 *   4) 打印 chunk 名与 PASS/FAIL；失败时给出**下一步该做什么**
 *
 *   ⚠️ 线上入口 chunk 名 **不等于** 本机 `vite build` 的 chunk 名 ——
 *   CF Pages 项目侧设了 `VITE_API_BASE_URL` 等环境变量，会改变产物内容哈希。
 *   别拿本机名字当线上期望值，用「chunk 里是否含 CURRENT_VERSION」判。
 */

import { spawnSync } from 'node:child_process'
import { readFileSync, mkdtempSync, existsSync, openSync, closeSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..')
const FRONTEND = join(REPO, 'frontend')

const args = process.argv.slice(2)
const LIVE = args.includes('--live')
const urlArg = args.find((a) => a.startsWith('--url='))
const LIVE_URL = (urlArg ? urlArg.slice('--url='.length) : 'https://offergo.pages.dev').replace(/\/+$/, '')

const results = []
let failed = false

function log(msg) {
  process.stdout.write(msg + '\n')
}

function section(title) {
  log('')
  log('─'.repeat(66))
  log(title)
  log('─'.repeat(66))
}

/** 本地工具的 JS 入口（用于绕开 shell，见 runTool 注释） */
const TOOLS = {
  'vue-tsc': join('vue-tsc', 'bin', 'vue-tsc.js'),
  vitest: join('vitest', 'vitest.mjs'),
  vite: join('vite', 'bin', 'vite.js'),
}

/**
 * 跑一条命令，透传输出；返回 { ok, out }。
 *
 * 两个都必须绕开的坑（2026-10-12 实测，写下来免得后人重踩）：
 *
 * ① **不要用 `shell: true`** —— Windows 上它要拉起 `cmd.exe`；在本机环境里
 *    这一步直接 `EBUSY` 失败，且 stdout/stderr 全空，表现为「三步全部空输出、
 *    2 秒秒退、状态全红」，极易被误读成「检查没通过」。
 *    → 改为用**当前 Node 直接执行工具自己的 JS 入口**（node_modules 里就有），
 *      完全不过 shell。
 *
 * ② **不要用管道捕获输出**（`encoding` / `stdio:'pipe'` / `execFileSync` 都算）
 *    —— 本机 Node 给子进程建管道会 `EBUSY`（实测 A/B/D/E/F/G 全挂）。
 *    只有 `stdio:'inherit'` 能跑通，但它拿不到输出，脚本就没法判断
 *    「构建是不是被沙箱的 safe-delete 拦下」。
 *    → 改用**文件句柄**当 stdio（实测可用，stdout/stderr 都能收到，非零退出码也拿得到）。
 */
function runTool(tool, args, cwd) {
  const entry = join(cwd, 'node_modules', TOOLS[tool])
  const logFile = join(mkdtempSync(join(tmpdir(), 'ig-run-')), 'out.txt')
  const fd = openSync(logFile, 'w')
  let r
  try {
    r = existsSync(entry)
      ? spawnSync(process.execPath, [entry, ...args], { cwd, stdio: ['ignore', fd, fd] })
      : spawnSync(`npx ${tool} ${args.join(' ')}`, { cwd, shell: true, stdio: ['ignore', fd, fd] })
  } finally {
    closeSync(fd)
  }

  let out = readFileSync(logFile, 'utf8')
  if (r.error) out += `\n[spawn error] ${r.error.code || ''} ${r.error.message}`
  process.stdout.write(out)
  return { ok: r.status === 0, out }
}

function record(name, ok, note) {
  results.push({ name, ok, note })
  if (!ok) failed = true
}

/** 从 changelog.ts 读当前版本号（单一来源，避免脚本里再写死一个版本） */
function readCurrentVersion() {
  const src = readFileSync(join(FRONTEND, 'src', 'changelog.ts'), 'utf8')
  const m = src.match(/CURRENT_VERSION\s*=\s*'([^']+)'/)
  if (!m) throw new Error('未能从 frontend/src/changelog.ts 解析出 CURRENT_VERSION')
  return m[1]
}

/** 无缓存拉取文本（排除 CDN / 本地缓存造成的「假旧」） */
async function fetchNoCache(url) {
  const bust = `${url}${url.includes('?') ? '&' : '?'}cb=${Date.now()}${Math.floor(Math.random() * 1e6)}`
  const res = await fetch(bust, {
    headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' },
  })
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`)
  return res.text()
}

// ────────────────────────────────────────────────────────────────────
// 主流程
// ────────────────────────────────────────────────────────────────────

let version
try {
  version = readCurrentVersion()
} catch (e) {
  log(`✗ ${e.message}`)
  process.exit(1)
}

log(`发布验收开始 · CURRENT_VERSION = ${version}`)

section('1/3  类型检查  vue-tsc --noEmit')
{
  const r = runTool('vue-tsc', ['--noEmit'], FRONTEND)
  record('vue-tsc', r.ok, r.ok ? '' : '类型检查未通过')
}

section('2/3  全量单测  vitest run')
{
  const r = runTool('vitest', ['run'], FRONTEND)
  record('vitest', r.ok, r.ok ? '' : '存在失败用例（含 designGuards / changelog 门禁）')
}

section('3/3  构建  vite build')
{
  let r = runTool('vite', ['build'], FRONTEND)

  // 沙箱环境守卫：准备阶段清 dist 被拦。判据是「准备阶段 + safe-delete」，
  // 命中时改用仓库外临时目录重试，拿到真实的构建结论。
  const sandboxBlocked =
    !r.ok && /safe-delete|SAFE_DELETE_BULK_CONFIRM_REQUIRED/i.test(r.out) && /prepare-out-dir|Cleaning|dist/i.test(r.out)

  if (sandboxBlocked) {
    const outDir = mkdtempSync(join(tmpdir(), 'ig-build-check-'))
    log('')
    log('⚠️  默认输出目录被沙箱的批量删除守卫拦下（safe-delete）——这是**环境守卫，不是构建坏了**。')
    log(`   改用仓库外临时目录重试：${outDir}`)
    log('')
    r = runTool('vite', ['build', '--outDir', outDir, '--emptyOutDir'], FRONTEND)
    record('vite build', r.ok, r.ok ? '（沙箱内：改用仓库外临时目录后通过）' : '构建失败')
  } else {
    record('vite build', r.ok, r.ok ? '' : '构建失败')
  }
}

// ── 线上部署验证（只读）──────────────────────────────────────────────
if (LIVE) {
  section(`线上部署验证  ${LIVE_URL}`)
  try {
    const html = await fetchNoCache(`${LIVE_URL}/`)
    const entry = (html.match(/assets\/index-[A-Za-z0-9_-]+\.js/) || [])[0]
    const css = (html.match(/assets\/index-[A-Za-z0-9_-]+\.css/) || [])[0]
    if (!entry) throw new Error('index.html 里找不到入口 chunk（站点结构变了？）')

    const entrySrc = await fetchNoCache(`${LIVE_URL}/${entry}`)
    const hasVersion = entrySrc.includes(version)

    log(`  入口 chunk : ${entry}`)
    if (css) log(`  样式 chunk : ${css}`)
    log(`  含 ${version} : ${hasVersion ? '是' : '否'}`)

    if (hasVersion) {
      log('')
      log(`  ✅ 线上已是 ${version}`)
      record('线上部署', true, '')
    } else {
      log('')
      log(`  ✗ 线上还不是 ${version} —— 部署没发生或没生效。按顺序排查：`)
      log('     1) 远端提交是否已在？   git ls-remote origin refs/heads/main')
      log('     2) 是不是缓存假象？     本脚本已带 no-cache + 随机查询串，基本可排除')
      log('     3) 构建能不能过？       看上面第 3/3 步')
      log('     4) 都不是 → 重触发一次构建（成本最低，实测约 1.5 分钟生效）：')
      log('          git commit --allow-empty -m "chore: 重新触发 Pages 构建" && git push origin main')
      log('     5) 仍不动 → 到 Cloudflare Pages 控制台看该 commit 的构建记录')
      log('        （免费版构建额度用尽 / Git 集成掉线都会表现为「push 了但站点不动」）')
      record('线上部署', false, '线上未生效')
    }
  } catch (e) {
    log(`  ✗ 探测失败：${e.message}`)
    log('    （若本机挂了 HTTP 代理，先排除代理拦截再下结论）')
    record('线上部署', false, e.message)
  }
}

// ── 汇总 ────────────────────────────────────────────────────────────
section('汇总')
for (const r of results) {
  log(`  ${r.ok ? '✅' : '❌'}  ${r.name}${r.note ? `  —— ${r.note}` : ''}`)
}
log('')
if (failed) {
  log('结论：❌ 未通过，**不要推送**。')
  process.exit(1)
}
log(`结论：✅ 全部通过${LIVE ? `（含线上 ${version}）` : ''}，可以推送。`)
if (!LIVE) {
  log('提示：推送后用 `--live` 再跑一次，确认线上真的换上了新产物。')
}

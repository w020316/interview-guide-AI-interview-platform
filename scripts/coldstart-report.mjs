#!/usr/bin/env node
/**
 * 冷启动观测报告：用保活工作流的运行时长，免费、持续地测量后端冷启动耗时
 *
 * ── 为什么用这个办法 ─────────────────────────────────────────────────
 * Render 免费层的实例日志要登录后台才能看，而**保活工作流每次运行本身就是一次
 * 冷启动实测**：它先 ping `/api/info` 直到拿到 2xx，再校验 `/api/health`。
 * 于是「Run 时长 − 约 6s 的固定开销」≈ 冷启动耗时。
 *
 * GitHub 的 schedule 虽然被限流到 3~6 小时一次（正因如此保活不可靠），
 * 但这些运行记录**恰好成了天然的低频采样**，且完全免费、无需任何凭据
 * （公共仓库的运行记录通过 GitHub 公开 API 即可读取）。
 *
 * 用途：改完 JVM/启动相关参数后，隔几小时跑一次本脚本，看冷启动分布是否下降。
 *
 * ── 统计口径（重要，别被假象误导）────────────────────────────────────
 * - **热态**（< 60s）：实例本来就没睡，不是冷启动，剔除。
 * - **成功的冷启动**：这才是真实测量值，参与分位数统计。
 * - **失败的运行**：时长等于工作流的唤醒预算（当前 480s），只能说明
 *   「冷启动 ≥ 480s」，是**下界而非测量值**，必须从分布里剔除，
 *   否则会把真实耗时**低估**。
 *   （2026-09-18 那批 ~216s 的失败同理：它们是当时 210s 预算截断的产物，
 *    不是 216s 的冷启动，故不计入基线。）
 *
 * ── 用法 ────────────────────────────────────────────────────────────
 *   node scripts/coldstart-report.mjs                 # 最近 30 次运行
 *   node scripts/coldstart-report.mjs --limit 50      # 最近 50 次
 *   node scripts/coldstart-report.mjs --json          # 输出 JSON（便于接监控）
 *
 * 可用环境变量覆盖默认值：
 *   GH_REPO      默认 w020316/interview-guide-AI-interview-platform
 *   GH_WORKFLOW  默认 keepalive.yml
 */

const REPO = process.env.GH_REPO || 'w020316/interview-guide-AI-interview-platform'
const WORKFLOW = process.env.GH_WORKFLOW || 'keepalive.yml'

/** 低于该时长视为「实例本来就是热的」，不计入冷启动统计 */
const WARM_THRESHOLD_SEC = 60

/**
 * 中位差在这个范围内不下结论。
 * Render 侧负载波动可让单次冷启动相差 100s+，不设噪声带会得出假结论。
 */
const NOISE_SEC = 60

/**
 * 2026-09-22 优化前基线：17 次**成功**的冷启动样本
 * （已剔除热态运行与撞预算的失败运行，口径见文件头）。
 */
const BASELINE = { count: 17, min: 209, median: 258, max: 406, truncated: 1 }

const args = process.argv.slice(2)
const asJson = args.includes('--json')
const limitIdx = args.indexOf('--limit')
const limit = limitIdx >= 0 ? Number(args[limitIdx + 1]) || 30 : 30

function parseUtc(s) {
  return Date.parse(s.replace(' ', 'T') + (s.endsWith('Z') ? '' : 'Z'))
}

function percentile(sorted, p) {
  if (sorted.length === 0) return NaN
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
  return sorted[i]
}

async function fetchRuns() {
  const url = `https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/runs?per_page=${Math.min(limit, 100)}`
  const res = await fetch(url, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'coldstart-report' },
  })
  if (!res.ok) {
    throw new Error(`读取运行记录失败：HTTP ${res.status}（公共仓库无需 token；若仓库转为私有则需鉴权）`)
  }
  const data = await res.json()
  return data.workflow_runs || []
}

function summarize(runs) {
  const rows = runs
    .filter((r) => r.run_started_at && r.updated_at)
    .map((r) => {
      const seconds = Math.round((parseUtc(r.updated_at) - parseUtc(r.run_started_at)) / 1000)
      const ok = r.conclusion === 'success'
      return {
        startedAt: r.run_started_at,
        conclusion: r.conclusion || r.status,
        seconds,
        // 热态 / 冷启动测量值 / 撞预算下界（失败）
        kind: seconds < WARM_THRESHOLD_SEC ? 'warm' : ok ? 'cold' : 'truncated',
        url: r.html_url,
      }
    })

  const cold = rows.filter((r) => r.kind === 'cold').map((r) => r.seconds).sort((a, b) => a - b)
  const stats = cold.length
    ? {
        count: cold.length,
        min: cold[0],
        p25: percentile(cold, 25),
        median: percentile(cold, 50),
        p75: percentile(cold, 75),
        max: cold[cold.length - 1],
      }
    : null

  return {
    rows,
    stats,
    warmCount: rows.filter((r) => r.kind === 'warm').length,
    truncatedCount: rows.filter((r) => r.kind === 'truncated').length,
  }
}

function printText({ rows, stats, warmCount, truncatedCount }) {
  console.log(`仓库    ：${REPO}`)
  console.log(`工作流  ：${WORKFLOW}`)
  console.log(`采样条数：${rows.length}（热态 ${warmCount}，冷启动测量值 ${stats?.count ?? 0}，撞预算 ${truncatedCount}）`)
  console.log()
  console.log('运行开始时间(UTC)     结论        时长     类型')
  console.log('─'.repeat(56))
  const label = { warm: '热态', cold: '冷启动', truncated: '≥下界' }
  for (const r of rows) {
    console.log(
      `${r.startedAt}  ${String(r.conclusion).padEnd(9)}  ${String(r.seconds).padStart(4)}s  ${label[r.kind]}`
    )
  }
  console.log()
  if (!stats) {
    console.log('样本里没有成功的冷启动记录，无法统计。')
    return
  }
  console.log('冷启动耗时分布（秒，仅计成功运行）')
  console.log(
    `  样本 ${stats.count}   最小 ${stats.min}   P25 ${stats.p25}   中位 ${stats.median}   P75 ${stats.p75}   最大 ${stats.max}`
  )
  if (truncatedCount > 0) {
    console.log(
      `  另有 ${truncatedCount} 次**未在唤醒预算内完成**（预算随版本变化：早期约 210s，当前 480s）——`
    )
    console.log('  它们的时长等于当时的预算，只能给出「冷启动 ≥ 该值」的下界，故不计入上面的分布。')
  }
  console.log()
  console.log('优化前基线（2026-09-22，口径相同）')
  console.log(
    `  样本 ${BASELINE.count}   最小 ${BASELINE.min}   中位 ${BASELINE.median}   最大 ${BASELINE.max}` +
      `   另有 ${BASELINE.truncated} 次未在预算内完成（≥480s）`
  )
  console.log()
  const delta = stats.median - BASELINE.median
  if (stats.count < 5) {
    console.log(`➖ 冷启动样本仅 ${stats.count} 次，不足以判断优化效果（建议 ≥5 次）`)
  } else if (delta <= -NOISE_SEC) {
    console.log(`✅ 中位冷启动比基线快 ${Math.abs(delta)}s（${((-delta / BASELINE.median) * 100).toFixed(1)}%）`)
  } else if (delta >= NOISE_SEC) {
    console.log(`⚠️ 中位冷启动比基线慢 ${delta}s（+${((delta / BASELINE.median) * 100).toFixed(1)}%）`)
  } else {
    console.log(`➖ 中位冷启动与基线基本持平（${delta > 0 ? '+' : ''}${delta}s，在 ±${NOISE_SEC}s 噪声带内）`)
  }
  console.log()
  console.log('提示：Render 侧负载波动可让单次冷启动相差 100s+，请积累 ≥5 次样本再下结论。')
}

const runs = await fetchRuns()
const summary = summarize(runs)
if (asJson) {
  console.log(JSON.stringify({ repo: REPO, workflow: WORKFLOW, baseline: BASELINE, ...summary }, null, 2))
} else {
  printText(summary)
}

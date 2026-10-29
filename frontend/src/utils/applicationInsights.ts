import { EMPTY } from './format'
import { asArray } from './safeData'

/**
 * 投递漏斗洞察（v1.86.0）—— 投递看板 `GET /api/application/board` 的 `funnelStages` / `response` / `silent*`。
 *
 * <p>纯数据整形 + 展示格式化，零网络、零外部依赖。
 *
 * ── 三条纪律 ────────────────────────────────────────────────
 * 1. **无数据 ≠ 0**：转化率分母为 0 时后端给 `null`，这里一路透传到 {@link formatRate}（显示 `—`）。
 *    写死成 `0%` 会让「还没投出」看起来像「投了没人回」。
 * 2. **不猜**：`sampleSize` 为 0 时平均/中位一律为 `null`，即使后端误传了数字也不展示。
 * 3. **形状兜底**：接口字段缺失/类型变化时退化为空列表，而不是让模板抛错整页白屏
 *    （v1.76.1 的教训：`x || 默认值` 兜不住「类型变了」）。
 */

export interface FunnelStage {
  id: string
  label: string
  count: number
  /** 本段 / 上一段；首段或上一段为 0 时为 null */
  conversionFromPrev: number | null
  /** 本段 / 已投出；首段或已投出为 0 时为 null */
  conversionFromSubmitted: number | null
}

export interface ResponseStats {
  /** 参与统计的记录数（投出时间与首次回音时间都有的才计入） */
  sampleSize: number
  /** 平均首次回音天数；样本为 0 时 null */
  avgDays: number | null
  /** 中位首次回音天数；样本为 0 时 null */
  medianDays: number | null
}

export interface ApplicationInsights {
  stages: FunnelStage[]
  response: ResponseStats
  silentCount: number
}

export const EMPTY_RESPONSE: ResponseStats = { sampleSize: 0, avgDays: null, medianDays: null }

/** 非负有限数；null/undefined/NaN/负数一律视为「没拿到」 */
function asDays(v: unknown): number | null {
  if (v == null) return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) && n >= 0 ? n : null
}

function asRecord(v: unknown): Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function normalizeStage(v: unknown): FunnelStage | null {
  const o = asRecord(v)
  if (typeof o.id !== 'string' || typeof o.label !== 'string') return null
  const count = asDays(o.count)
  if (count === null) return null
  return {
    id: o.id,
    label: o.label,
    count: Math.trunc(count),
    conversionFromPrev: asDays(o.conversionFromPrev),
    conversionFromSubmitted: asDays(o.conversionFromSubmitted),
  }
}

export function normalizeInsights(raw: unknown): ApplicationInsights {
  const board = asRecord(raw)
  const stages = asArray<unknown>(board.funnelStages)
    .map(normalizeStage)
    .filter((s): s is FunnelStage => s !== null)

  const r = asRecord(board.response)
  const sampleSize = Math.trunc(asDays(r.sampleSize) ?? 0)
  const response: ResponseStats = {
    sampleSize,
    // 样本为 0 时不展示任何「平均」，即使后端误传了数字
    avgDays: sampleSize > 0 ? asDays(r.avgDays) : null,
    medianDays: sampleSize > 0 ? asDays(r.medianDays) : null,
  }

  return { stages, response, silentCount: Math.trunc(asDays(board.silentCount) ?? 0) }
}

/**
 * 天数展示：`null` → 占位符 `—`；0 → 「当天」；不足 1 天 → 「不足 1 天」；其余保留一位小数。
 *
 * <p>`0` 是**合法值**（当天就回复了），必须与 `null`（没有样本）区分开。
 */
export function formatDays(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value < 0) return EMPTY
  if (value === 0) return '当天'
  if (value < 1) return '不足 1 天'
  return Number.isInteger(value) ? `${value} 天` : `${value.toFixed(1)} 天`
}

export interface StageDrop {
  from: FunnelStage
  to: FunnelStage
  lost: number
}

/** 流失人数最多的一步（并列取靠前的一段）；没有流失时返回 null */
export function biggestDrop(stages: FunnelStage[]): StageDrop | null {
  let best: StageDrop | null = null
  for (let i = 1; i < stages.length; i++) {
    const lost = stages[i - 1].count - stages[i].count
    if (lost <= 0) continue
    if (best === null || lost > best.lost) {
      best = { from: stages[i - 1], to: stages[i], lost }
    }
  }
  return best
}

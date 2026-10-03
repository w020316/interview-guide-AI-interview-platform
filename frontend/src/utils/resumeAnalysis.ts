/**
 * 简历分析结果判定（v1.46.x UX 修复 · P0）
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * 简历分析链路在 AI 输出无法解析时，后端会落库一段**兜底 JSON**，历史上是：
 *   {"strengths": [], "dimensions": [], "improvements": ["AI 返回内容无法解析，请稍后重试"], "overallScore": 0}
 * 因为它本身是**合法 JSON**，前端的解析失败提示不会触发；而 `overallScore: 0`
 * 又和「真的考了 0 分」在数据上**完全无法区分**，导致：
 *   列表显示「0 分」+ 低分红底 → 详情评分区被 `v-if` 的 falsy 判断整块隐藏（近乎空白）。
 *
 * 本模块提供**纯函数**判定「这份分析是不是失败/无法解析」，供列表卡片与详情弹窗
 * 渲染明确的失败态，而不是把失败伪装成 0 分。
 *
 * ── 兼容的三种形状 ────────────────────────────────────────────────────
 * 1. 新形状（后端修复后）：`analysisResult` 带 `"error":"AI_PARSE_FAILED"`，`overallScore` 落 `null`；
 * 2. 历史脏数据：`overallScore: 0` + 兜底 improvements（含「无法解析」）；
 * 3. 正常结果 / 真实 0 分：`dimensions` 有内容 → **绝不能**被判为失败。
 *
 * ⚠️ 不要用 `overallScore === 0` 判定失败 —— 会误伤真实 0 分。
 */

/** 后端失败兜底串携带的错误码 */
export const AI_PARSE_FAILED = 'AI_PARSE_FAILED'

/** 旧兜底 improvements 里的特征文案 */
const UNPARSEABLE_HINT = '无法解析'

/** 判定所需的最小字段形状（列表项与详情对象都满足） */
export interface ResumeAnalysisLike {
  /** 新形状：顶层错误码（若后端额外暴露） */
  error?: string | null
  /** 综合分；失败时后端落 null */
  overallScore?: number | null
  /** AI 分析结果 JSON 字符串（可能未解析/脏） */
  analysisResult?: string | null
}

/** 内部：安全解析 analysisResult，返回对象或 null */
function parseAnalysis(raw: string | null | undefined): Record<string, unknown> | null {
  if (raw == null || String(raw).trim() === '') return null
  try {
    const obj: unknown = JSON.parse(String(raw))
    return obj && typeof obj === 'object' && !Array.isArray(obj)
      ? (obj as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/**
 * 判定这份简历分析是否为「失败 / 无法解析」。
 *
 * 判定顺序：
 * - 顶层 `error === 'AI_PARSE_FAILED'` → 失败；
 * - 无 `analysisResult`（缺失/空）→ 失败（不应出现，避免渲染空白详情）；
 * - `analysisResult` 不是合法 JSON → 失败；
 * - 解析出的对象 `error === 'AI_PARSE_FAILED'` → 失败；
 * - `dimensions` 为空 且（`improvements` 为空 或 首条含「无法解析」）→ 失败（兜底串特征）。
 */
export function isParseFailed(record?: ResumeAnalysisLike | null): boolean {
  if (!record) return false
  if (record.error === AI_PARSE_FAILED) return true

  const parsed = parseAnalysis(record.analysisResult)
  if (parsed === null) return true

  if (parsed.error === AI_PARSE_FAILED) return true

  const dims = Array.isArray(parsed.dimensions) ? parsed.dimensions : []
  const imps = Array.isArray(parsed.improvements) ? parsed.improvements : []
  if (dims.length === 0) {
    if (imps.length === 0) return true
    if (String(imps[0] ?? '').includes(UNPARSEABLE_HINT)) return true
  }
  return false
}

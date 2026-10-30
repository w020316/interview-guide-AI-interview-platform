/**
 * 知识库「快速检索」结果归一化（G7，2026-10-18）。
 *
 * <p>为什么需要这一层：`GET /api/knowledge/search` 返回的 `Result<String>` 里，
 * `data` 是一段 **JSON 字符串**（后端 `objectMapper.writeValueAsString(result)`），
 * 不是数组。直接 `res.length` 会拿到字符数、直接遍历会按字符遍历 —— 都是静默错。
 *
 * <p>另外后端为兼容历史契约同时给了三个相似度字段，语义**相反**：
 * - `distance`（余弦距离，**越小越相似**）
 * - `similarity`（1 - distance，**越大越相似**）
 * - `score`（历史字段，取值同 distance —— 名字叫 score 但语义是距离）
 *
 * 所以前端只认 `similarity`，并在这里统一算出用于展示的百分比，
 * 避免各视图对 `score` 的理解再次分叉（v1.34.1 的 P2-10 就是这个坑）。
 */

export interface SearchHit {
  id: string
  content: string
  /** 归一化相似度 0~1；后端没给 distance 时为 null（**不是 0**） */
  similarity: number | null
  /** 是否本人导入（G-01：仅自己的条目才显示删除入口，共享知识不可删） */
  owned: boolean
}

function toNumberOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/** 相似度的契约是 0~1；脏数据（负 distance / 后端算出 >1）在解析层就钳住，
 *  否则下游每处展示都要各自钳一次 —— 那正是「同一口径散落多处」的老问题。 */
function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v))
}

/**
 * 解析检索结果。
 *
 * <p>入参允许三种形态，因为它在不同层会被不同地传进来：
 * 字符串（后端原样）、已解析的数组（被上层 parse 过）、以及异常值（null/对象）。
 * 任何无法解析的情况一律返回空数组 —— 检索失败应当表现为「没有结果」，
 * 而不是把异常抛到渲染层。
 */
export function parseSearchHits(raw: unknown): SearchHit[] {
  let arr: unknown = raw

  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) return []
    try {
      arr = JSON.parse(text)
    } catch {
      return []
    }
  }

  if (!Array.isArray(arr)) return []

  return arr
    .filter((it): it is Record<string, unknown> => !!it && typeof it === 'object')
    .map((it) => {
      const distance = toNumberOrNull(it.distance)
      const similarity = toNumberOrNull(it.similarity)
      return {
        id: it.id == null ? '' : String(it.id),
        content: typeof it.content === 'string' ? it.content : '',
        // 优先用后端算好的 similarity；只有 distance 时按 1-d 兜底；
        // 两者都没有 → null，渲染成「—」而不是 0。结果统一钳到 0~1。
        similarity: similarity != null
          ? clamp01(similarity)
          : (distance == null ? null : clamp01(1 - distance)),
        owned: it.owned === true,
      }
    })
    // 空内容是脏数据（不该出现在向量库），留着只会渲染出一个空卡片
    .filter((h) => h.content.trim().length > 0)
}

/** 相似度 → 展示文案。null/缺失返回 '—'（无数据 ≠ 0） */
export function formatSimilarity(similarity: number | null): string {
  if (similarity == null) return '—'
  return Math.round(Math.max(0, Math.min(1, similarity)) * 100) + '%'
}

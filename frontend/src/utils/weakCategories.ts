/**
 * 薄弱分类聚合工具
 *
 * 错题本“针对错题重新练习”入口的数据预处理：
 * - 从错题列表中按分类聚合，依据平均分升序挑选最薄弱的前 N 个分类，作为
 *   出题接口的 focusCategories（跨场自适应聚焦弱项）
 * - 统计出现频次最高的岗位，用于预填面试准备页的目标岗位
 */

/** 参与弱项聚合的题目最少字段 */
export interface WeakSource {
  category?: string | null
  evaluationScore?: number | null
  jobDescription?: string | null
}

/**
 * 从错题列表中提取最薄弱的分类（按平均分升序，取前 max 个）
 * - 无分类或缺分类的题目被忽略
 * - 同分类多题时按平均分排序，而非简单计数，避免“错得多但均分尚可”的分类误排第一
 * @returns 分类名称数组，空列表表示无有效分类
 */
export function extractWeakCategories<T extends WeakSource>(items: T[], max = 3): string[] {
  const map = new Map<string, { count: number; sum: number }>()
  for (const it of items) {
    if (!it.category) continue
    const c = map.get(it.category) || { count: 0, sum: 0 }
    c.count++
    if (it.evaluationScore != null) c.sum += it.evaluationScore
    map.set(it.category, c)
  }
  return [...map.entries()]
    .map(([cat, st]) => ({ cat, avg: st.count ? st.sum / st.count : 0 }))
    .sort((a, b) => a.avg - b.avg)
    .slice(0, max)
    .map((x) => x.cat)
}

/**
 * 统计出现频次最高的岗位（有 `jobDescription` 的项中取众数）
 * @returns 岗位名称，无任何岗位信息时返回空串
 */
export function mostFrequentJob<T extends WeakSource>(items: T[]): string {
  const map = new Map<string, number>()
  for (const it of items) {
    const j = it.jobDescription?.trim()
    if (!j) continue
    map.set(j, (map.get(j) || 0) + 1)
  }
  let best = ''
  let bestN = 0
  for (const [j, n] of map) {
    // 频次相同取先出现的（保持稳定）
    if (n > bestN) {
      best = j
      bestN = n
    }
  }
  return best
}
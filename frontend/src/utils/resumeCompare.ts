/**
 * 简历多版本对比（v1.25.0 新功能）
 * 纯逻辑：对齐两个版本的维度并计算差异，供对比弹窗渲染，可单测。
 */

export interface ResumeVersion {
  id: number
  targetJob?: string | null
  createdAt?: string | null
  overallScore: number
  dimensions: Array<{ name: string; score: number }>
}

export interface DimDiff {
  name: string
  /** 版本 A（较早）分数，缺省为 null */
  a: number | null
  /** 版本 B（较晚）分数，缺省为 null */
  b: number | null
  /** B - A，任一缺省为 null */
  diff: number | null
  /** 哪边更好：a/b/tie；任一缺省为 none */
  better: 'a' | 'b' | 'tie' | 'none'
}

export interface CompareResult {
  /** 综合分差异 B - A，四舍五入取整 */
  overallDiff: number
  rows: DimDiff[]
}

/** 维度对齐 + 差异计算：A 为基线（较早），B 为对比（较晚） */
export function compareResume(a: ResumeVersion, b: ResumeVersion): CompareResult {
  const overallDiff = Math.round((b.overallScore || 0) - (a.overallScore || 0))

  const byName = new Map<string, { a?: number; b?: number }>()
  for (const d of a.dimensions || []) {
    if (!d?.name) continue
    byName.set(d.name, { a: d.score })
  }
  for (const d of b.dimensions || []) {
    if (!d?.name) continue
    const row = byName.get(d.name)
    if (row) row.b = d.score
    else byName.set(d.name, { b: d.score })
  }

  const rows: DimDiff[] = [...byName.entries()].map(([name, v]) => {
    const hasBoth = v.a != null && v.b != null
    const diff = hasBoth ? Math.round((v.b! - v.a!) * 10) / 10 : null
    const better: DimDiff['better'] = !hasBoth
      ? 'none'
      : diff! > 0 ? 'b' : diff! < 0 ? 'a' : 'tie'
    return { name, a: v.a ?? null, b: v.b ?? null, diff, better }
  })

  return { overallDiff, rows }
}

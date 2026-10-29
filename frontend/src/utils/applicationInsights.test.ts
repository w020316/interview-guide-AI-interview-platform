import { describe, it, expect } from 'vitest'
import {
  normalizeInsights,
  formatDays,
  biggestDrop,
  EMPTY_RESPONSE,
  type FunnelStage,
} from './applicationInsights'

const BOARD = {
  funnelStages: [
    { id: 'submitted', label: '已投出', count: 6, conversionFromPrev: null, conversionFromSubmitted: null },
    { id: 'viewed', label: '被查看', count: 5, conversionFromPrev: 0.833, conversionFromSubmitted: 0.833 },
    { id: 'replied', label: '有回复', count: 3, conversionFromPrev: 0.6, conversionFromSubmitted: 0.5 },
    { id: 'interview', label: '进面试', count: 2, conversionFromPrev: 0.667, conversionFromSubmitted: 0.333 },
    { id: 'offer', label: '拿 Offer', count: 1, conversionFromPrev: 0.5, conversionFromSubmitted: 0.167 },
  ],
  response: { sampleSize: 3, avgDays: 5.0, medianDays: 4.0 },
  silentCount: 2,
}

describe('normalizeInsights', () => {
  it('正常载荷：原样整形', () => {
    const r = normalizeInsights(BOARD)
    expect(r.stages).toHaveLength(5)
    expect(r.stages[0].conversionFromPrev).toBeNull()
    expect(r.stages[4].conversionFromSubmitted).toBe(0.167)
    expect(r.response).toEqual({ sampleSize: 3, avgDays: 5, medianDays: 4 })
    expect(r.silentCount).toBe(2)
  })

  it('字段缺失/类型异常时退化为空，不抛错（形状兜底）', () => {
    for (const bad of [null, undefined, 'oops', 42, [], { funnelStages: 'x', response: 7 }]) {
      const r = normalizeInsights(bad)
      expect(r.stages).toEqual([])
      expect(r.response).toEqual(EMPTY_RESPONSE)
      expect(r.silentCount).toBe(0)
    }
  })

  it('丢弃形状不合法的阶段，保留合法的（一条坏数据不拖垮整块）', () => {
    const r = normalizeInsights({
      funnelStages: [
        { id: 'submitted', label: '已投出', count: 3, conversionFromPrev: null, conversionFromSubmitted: null },
        { id: 'viewed' },                                  // 缺 label
        { id: 'replied', label: '有回复', count: 'NaN' },   // count 不是数字
        { id: 'offer', label: '拿 Offer', count: 0, conversionFromPrev: null, conversionFromSubmitted: 0 },
      ],
    })
    expect(r.stages.map((s) => s.id)).toEqual(['submitted', 'offer'])
    // 0 是合法计数，不能被当成「没拿到」而丢弃
    expect(r.stages[1].count).toBe(0)
  })

  it('样本为 0 时即使后端误传了平均天数，也不展示', () => {
    const r = normalizeInsights({ response: { sampleSize: 0, avgDays: 5, medianDays: 4 } })
    expect(r.response.sampleSize).toBe(0)
    expect(r.response.avgDays).toBeNull()
    expect(r.response.medianDays).toBeNull()
  })

  it('平均天数 0 是合法值（当天回复），不得变成 null', () => {
    const r = normalizeInsights({ response: { sampleSize: 1, avgDays: 0, medianDays: 0 } })
    expect(r.response.avgDays).toBe(0)
    expect(r.response.medianDays).toBe(0)
  })
})

describe('formatDays', () => {
  it('null / undefined / 非法值 → 占位符', () => {
    for (const v of [null, undefined, NaN, Infinity, -1]) {
      expect(formatDays(v as number)).toBe('—')
    }
  })

  it('0 → 当天，与 null 区分开', () => {
    expect(formatDays(0)).toBe('当天')
  })

  it('不足 1 天单独措辞（避免显示 0.4 天）', () => {
    expect(formatDays(0.4)).toBe('不足 1 天')
  })

  it('整数与小数分别格式化', () => {
    expect(formatDays(3)).toBe('3 天')
    expect(formatDays(4.5)).toBe('4.5 天')
  })
})

describe('biggestDrop', () => {
  const stage = (id: string, count: number): FunnelStage => ({
    id,
    label: id,
    count,
    conversionFromPrev: null,
    conversionFromSubmitted: null,
  })

  it('找出流失人数最多的一步', () => {
    const d = biggestDrop([stage('a', 6), stage('b', 5), stage('c', 3), stage('d', 2), stage('e', 1)])
    expect(d).not.toBeNull()
    expect(d!.from.id).toBe('b')
    expect(d!.to.id).toBe('c')
    expect(d!.lost).toBe(2)
  })

  it('没有任何流失时返回 null（不硬编一个「最大流失」）', () => {
    expect(biggestDrop([stage('a', 3), stage('b', 3)])).toBeNull()
    expect(biggestDrop([stage('a', 3)])).toBeNull()
    expect(biggestDrop([])).toBeNull()
  })

  it('并列时取靠前的一段（输出确定）', () => {
    const d = biggestDrop([stage('a', 5), stage('b', 3), stage('c', 1)])
    expect(d!.from.id).toBe('a')
    expect(d!.to.id).toBe('b')
    expect(d!.lost).toBe(2)
  })
})

import { describe, it, expect } from 'vitest'
import { parseSearchHits, formatSimilarity } from './knowledgeSearch'

/**
 * G7（2026-10-18）：`/api/knowledge/search` 的 data 是一段 **JSON 字符串**，
 * 不是数组。这一层的作用就是把「字符串 / 已解析 / 脏数据」三种形态统一成数组。
 */
describe('parseSearchHits', () => {
  it('解析后端原样返回的 JSON 字符串', () => {
    const raw = '[{"id":"a1","content":"HashMap 底层是数组+链表","distance":0.2,"similarity":0.8}]'
    expect(parseSearchHits(raw)).toEqual([
      { id: 'a1', content: 'HashMap 底层是数组+链表', similarity: 0.8 },
    ])
  })

  it('空数组字符串 → 空数组（检索无结果不是错误）', () => {
    expect(parseSearchHits('[]')).toEqual([])
  })

  it('非法 JSON → 空数组，不抛异常', () => {
    expect(parseSearchHits('这不是 JSON')).toEqual([])
  })

  it('null / 空串 / 对象 → 空数组', () => {
    expect(parseSearchHits(null)).toEqual([])
    expect(parseSearchHits('')).toEqual([])
    expect(parseSearchHits({ a: 1 })).toEqual([])
  })

  it('已经是数组时直接归一（上层可能先 parse 过）', () => {
    const arr = [{ id: 1, content: '  Spring 事务  ', similarity: 0.5 }]
    expect(parseSearchHits(arr)).toEqual([{ id: '1', content: '  Spring 事务  ', similarity: 0.5 }])
  })

  it('只有 distance 时按 1-d 兜底（distance 越小越相似）', () => {
    const raw = '[{"id":"x","content":"A","distance":0.25}]'
    expect(parseSearchHits(raw)[0].similarity).toBeCloseTo(0.75, 5)
  })

  it('distance 为负数时相似度下限钳到 0，不出现 >100%', () => {
    const raw = '[{"id":"x","content":"A","distance":-0.3}]'
    expect(parseSearchHits(raw)[0].similarity).toBe(1)
  })

  it('两个相似度字段都没有 → similarity 为 null（不是 0）', () => {
    const raw = '[{"id":"x","content":"A"}]'
    expect(parseSearchHits(raw)[0].similarity).toBeNull()
  })

  it('优先用后端给的 similarity，不被 score 误导（score 语义是 distance）', () => {
    const raw = '[{"id":"x","content":"A","distance":0.9,"similarity":0.1,"score":0.9}]'
    expect(parseSearchHits(raw)[0].similarity).toBe(0.1)
  })

  it('过滤空内容条目（脏数据不渲染空卡片）', () => {
    const raw = '[{"id":"a","content":""},{"id":"b","content":"  "},{"id":"c","content":"有效"}]'
    expect(parseSearchHits(raw).map((h) => h.id)).toEqual(['c'])
  })
})

describe('formatSimilarity', () => {
  it('0~1 映射为百分比', () => {
    expect(formatSimilarity(0.8)).toBe('80%')
    expect(formatSimilarity(0.125)).toBe('13%')
  })

  it('null 显示 —（无数据 ≠ 0）', () => {
    expect(formatSimilarity(null)).toBe('—')
  })

  it('越界值被钳到 0~100%', () => {
    expect(formatSimilarity(-1)).toBe('0%')
    expect(formatSimilarity(2)).toBe('100%')
  })
})

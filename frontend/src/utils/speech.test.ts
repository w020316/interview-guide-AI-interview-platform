import { describe, it, expect } from 'vitest'
import { computeSpeechMetrics, type SpeechChunk } from './speech'

/** 构造连续识别的分段时间戳（文本不足时复用最后一段，确保每段均有有效文本） */
function chunks(times: number[], texts: string[]): SpeechChunk[] {
  return times.map((ts, i) => ({ ts, text: texts[i] ?? (texts[texts.length - 1] ?? '') }))
}

describe('speech - computeSpeechMetrics', () => {
  it('未识别到有效语音时提示重试', () => {
    const m = computeSpeechMetrics([{ ts: 1000, text: '' }], '')
    expect(m.charCount).toBe(0)
    expect(m.durationSec).toBe(0)
    expect(m.feedback).toContain('未能识别到有效语音')
  })

  it('正确计算时长与字数', () => {
    const m = computeSpeechMetrics(
      chunks([0, 2000, 4000, 6000], ['我', '我的项目', '我的项目用了', '我的项目用了微服务']),
      '我的项目用了微服务'
    )
    expect(m.charCount).toBe(9)
    expect(m.durationSec).toBe(6)
  })

  it('语速落在健康区间（150-260 字/分）判定为适中', () => {
    // 60s 内说 200 字 → 200 字/分
    const m = computeSpeechMetrics(chunks([0, 60000], ['x'.repeat(200)]), 'x'.repeat(200))
    expect(m.rateVerdict).toBe('适中')
    expect(m.ratePerMin).toBe(200)
  })

  it('语速过快判定为偏快', () => {
    // 30s 内说 200 字 → 400 字/分
    const m = computeSpeechMetrics(chunks([0, 30000], ['x'.repeat(200)]), 'x'.repeat(200))
    expect(m.rateVerdict).toBe('偏快')
  })

  it('语速过慢（<120 字/分）判定为太慢并给出建议', () => {
    // 60s 内仅 3 字 → 3 字/分
    const m = computeSpeechMetrics(chunks([0, 60000], ['一二三']), '一二三')
    expect(m.rateVerdict).toBe('太慢')
    expect(m.feedback).toContain('提快节奏')
  })

  it('相邻有效片段间隔超过 2.5s 计为一次停顿', () => {
    // 最后一次有效片段在 0ms，再一次有效在中途 0.6s，然后停 5s 后继续
    const m = computeSpeechMetrics(
      chunks([0, 600, 5600], ['开始', '开始讲', '停顿后继续']),
      '开始讲停顿后继续'
    )
    expect(m.pauseCount).toBe(1)
  })

  it('连续无停顿的语速为适中且反馈正面', () => {
    const m = computeSpeechMetrics(chunks([0, 60000], ['x'.repeat(200)]), 'x'.repeat(200))
    expect(m.feedback).toContain('良好')
  })
})
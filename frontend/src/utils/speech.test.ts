import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  computeSpeechMetrics,
  createSpeechRecorder,
  isSpeechSupported,
  type SpeechChunk,
  type SpeechMetrics,
} from './speech'

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

describe('speech - createSpeechRecorder / isSpeechSupported', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    delete (window as any).SpeechRecognition
    delete (window as any).webkitSpeechRecognition
  })

  /** 注册一个可控的假识别器，供生命周期测试驱动 */
  function mockRecognition() {
    const rec: any = {
      lang: '',
      continuous: false,
      interimResults: false,
      onstart: null,
      onresult: null,
      onend: null,
      onerror: null,
      start: vi.fn(),
      stop: vi.fn(),
      abort: vi.fn(),
    }
    ;(window as any).SpeechRecognition = class {
      constructor() {
        return rec
      }
    }
    return rec
  }

  it('浏览器不支持时 supported 为 false 且 start 直接返回 false', () => {
    expect(isSpeechSupported()).toBe(false)
    const r = createSpeechRecorder()
    expect(r.supported).toBe(false)
    expect(r.start()).toBe(false)
  })

  it('start 后触发 onstart，stop 后回调表达指标', () => {
    const rec = mockRecognition()
    let metrics: SpeechMetrics | null = null
    const r = createSpeechRecorder({ onMetrics: (m) => (metrics = m) })
    expect(r.supported).toBe(true)

    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)

    expect(r.start()).toBe(true)
    expect(rec.start).toHaveBeenCalledTimes(1)
    rec.onstart && rec.onstart()

    now = 3000
    rec.onresult && rec.onresult({ resultIndex: 0, results: { length: 1, 0: { isFinal: true, 0: { transcript: '你好' } } } })
    now = 4000
    rec.onresult && rec.onresult({ resultIndex: 1, results: { length: 1, 0: { isFinal: true, 0: { transcript: '世界' } } } })

    // 录音中再次 start 返回 true 但不重复触发底层 start
    expect(r.start()).toBe(true)
    expect(rec.start).toHaveBeenCalledTimes(1)

    r.stop()
    expect(rec.stop).toHaveBeenCalledTimes(1)
    rec.onend && rec.onend()
    expect(metrics).not.toBeNull()
    expect(metrics!.charCount).toBe(4)

    // 停止后重复 stop 不再触发
    r.stop()
    expect(rec.stop).toHaveBeenCalledTimes(1)
  })

  it('onerror 不同错误码给出对应提示', () => {
    const rec = mockRecognition()
    const errs: string[] = []
    const r = createSpeechRecorder({ onError: (m) => errs.push(m) })
    r.start()
    rec.onstart && rec.onstart()
    rec.onerror && rec.onerror({ error: 'not-allowed' })
    rec.onerror && rec.onerror({ error: 'network' })
    rec.onerror && rec.onerror({ error: 'no-speech' })
    rec.onerror && rec.onerror({ error: 'aborted' })
    expect(errs).toHaveLength(4)
    expect(errs[0]).toContain('未授权')
    expect(errs[1]).toContain('网络')
    expect(errs[2]).toContain('未检测到语音')
    expect(errs[3]).toContain('aborted')
  })

  it('cancel 主动终止且不触发指标回调', () => {
    const rec = mockRecognition()
    let metrics: SpeechMetrics | null = null
    const r = createSpeechRecorder({ onMetrics: (m) => (metrics = m) })
    r.start()
    rec.onstart && rec.onstart()
    r.cancel()
    expect(rec.abort).toHaveBeenCalled()
    // 识别器随后 onend，但因已 recording=false 不再回调指标
    rec.onend && rec.onend()
    expect(metrics).toBeNull()
  })

  it('isSpeechSupported 与识别器存在性一致', () => {
    expect(isSpeechSupported()).toBe(false)
    mockRecognition()
    expect(isSpeechSupported()).toBe(true)
  })
})
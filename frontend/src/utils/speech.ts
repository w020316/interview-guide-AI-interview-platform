/**
 * 语音作答（ASR）工具
 * - 基于浏览器原生 Web Speech API（webkitSpeechRecognition），免费、无需后端，中文实时转写
 * - computeSpeechMetrics：依据「分段时间戳序列 + 最终文本」计算语速/卡顿等表达指标（纯函数，可单测）
 * - createSpeechRecorder：封装识别器生命周期，回调转写文本与指标
 */

/** 识别过程中的一个分段时间戳与当时的转写文本 */
export interface SpeechChunk {
  /** 相对录音开始的毫秒时间戳 */
  ts: number
  text: string
}

/** 表达分析结果 */
export interface SpeechMetrics {
  /** 总时长（秒） */
  durationSec: number
  /** 有效字数（去空白后的中文字符数） */
  charCount: number
  /** 语速（字/分钟） */
  ratePerMin: number
  /** 明显停顿次数（相邻有效片段间隔 > 阈值） */
  pauseCount: number
  /** 语速评价：偏慢/适中/偏快 */
  rateVerdict: '太慢' | '偏慢' | '适中' | '偏快'
  /** 简短建议 */
  feedback: string
}

/** 停顿阈值（毫秒） */
const PAUSE_MS = 2500
/** 语速健康区间（字/分钟，中文口语常见值） */
const RATE_MIN = 150
const RATE_MAX = 260
/** 相邻片段最小有效间隔，小于此视为连续（毫秒） */
const MIN_SEG_GAP = 500

/** 统计文本有效字数（中文为主，去除空白） */
function countChars(text: string): number {
  return (text || '').replace(/\s+/g, '').length
}

/**
 * 技术术语纠正（v1.28.0）：修正 Web Speech API 对英文技术术语的常见误转写/大小写，
 * 对齐主流 AI 面试工具的语音识别优化。纯函数，可单测。
 * 如 "transformer"→"Transformer"、"deepseek"→"DeepSeek"、"变换者"→"Transformer"。
 */
const TECH_TERM_FIXES: ReadonlyArray<[RegExp, string]> = [
  [/\btransformer\b/gi, 'Transformer'],
  [/变换者/g, 'Transformer'],
  [/\bdeep[ ]?seek\b/gi, 'DeepSeek'],
  [/深度求索/g, 'DeepSeek'],
  [/\brag\b/gi, 'RAG'],
  [/\b(?:kubernetes|k8s)\b/gi, 'Kubernetes'],
  [/\bjavascript\b/gi, 'JavaScript'],
  [/\btypescript\b/gi, 'TypeScript'],
  [/\bspringbo?ot\b/gi, 'Spring Boot'],
  [/\bredis\b/gi, 'Redis'],
  [/\bmysql\b/gi, 'MySQL'],
  [/\bmongodb\b/gi, 'MongoDB'],
  [/\blinux\b/gi, 'Linux'],
]

/** 对转写文本应用技术术语纠正 */
export function correctTechTerms(text: string): string {
  if (!text) return text
  let out = text
  for (const [re, to] of TECH_TERM_FIXES) out = out.replace(re, to)
  return out
}

/**
 * 计算语速/卡顿等表达指标。
 * @param chunks 识别分段时间戳序列（未被清理的原始序列）
 * @param finalText 识别完成的最终文本
 */
export function computeSpeechMetrics(chunks: SpeechChunk[], finalText: string): SpeechMetrics {
  const n = countChars(finalText)

  // 用去重后的有效时间戳估算时长：取最后一个有效文本片段时间，否则用首尾差
  const lastTs = chunks.filter((c) => countChars(c.text) > 0).pop()?.ts ?? 0
  const firstTs = chunks.find((c) => countChars(c.text) > 0)?.ts ?? 0
  const durationSec = lastTs > firstTs ? (lastTs - firstTs) / 1000 : 0

  // 排列停顿：符号相同的片段之间（上一次有效字符出现的时刻 → 本次有效字符出现的时刻）间隔超过阈值计一次停顿
  let pauseCount = 0
  let lastActive = 0
  for (const c of chunks) {
    if (countChars(c.text) === 0) continue
    if (lastActive > 0 && c.ts - lastActive >= PAUSE_MS) pauseCount++
    lastActive = c.ts
  }

  const minutes = durationSec > 0 ? durationSec / 60 : 0
  const ratePerMin = minutes > 0 ? Math.round(n / minutes) : 0

  let rateVerdict: SpeechMetrics['rateVerdict']
  if (durationSec === 0 || n === 0) {
    rateVerdict = '适中'
  } else if (ratePerMin < 120) {
    rateVerdict = '太慢'
  } else if (ratePerMin < RATE_MIN) {
    rateVerdict = '偏慢'
  } else if (ratePerMin > RATE_MAX) {
    rateVerdict = '偏快'
  } else {
    rateVerdict = '适中'
  }

  let feedback: string
  if (durationSec === 0 || n === 0) {
    feedback = '未能识别到有效语音，请确认麦克风权限已开启、环境安静后重试。'
  } else {
    const parts: string[] = []
    if (rateVerdict === '偏快') parts.push('语速偏快，建议放慢、突出重点')
    if (rateVerdict === '偏慢' || rateVerdict === '太慢') parts.push('语速偏慢，可适当提快节奏、增强自信')
    if (pauseCount >= 3) parts.push(`出现过 ${pauseCount} 次明显停顿，建议减少口头宕机`)
    feedback = parts.length ? parts.join('；') + '。' : '语速与流畅度表现良好，继续加油！'
  }

  return { durationSec, charCount: n, ratePerMin, pauseCount, rateVerdict, feedback }
}

/** 识别器能力类型声明（Web Speech API 未纳入 TS 标准库） */
interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: { resultIndex: number; results: { length: number; [i: number]: { isFinal: boolean; 0: { transcript: string } } }; resultTime?: number }) => void) | null
  onstart: (() => void) | null
  onend: (() => void) | null
  onerror: ((e: { error: string }) => void) | null
  start: () => void
  stop: () => void
  abort: () => void
}

/** 构造识别器；浏览器不支持时返回 null */
function createRecognition(): SpeechRecognitionLike | null {
  const w = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognitionLike
    webkitSpeechRecognition?: new () => SpeechRecognitionLike
  }
  const Ctor = w.SpeechRecognition || w.webkitSpeechRecognition
  if (!Ctor) return null
  return new Ctor()
}

export interface SpeechRecorderOptions {
  onFinalText?: (text: string) => void
  onMetrics?: (metrics: SpeechMetrics) => void
  onError?: (msg: string) => void
  lang?: string
}

/** 是否支持语音识别 */
export function isSpeechSupported(): boolean {
  return createRecognition() != null
}

/**
 * 创建语音识别记录器。
 * - 返回 start/stop/isRecording；录音期间持续回调需由调用方在 onFinalText 累积
 * - stop 时自动计算表达指标并回调 onMetrics
 */
export function createSpeechRecorder(opts: SpeechRecorderOptions = {}) {
  const recognition = createRecognition()
  const supported = recognition != null

  let recording = false
  let finalized = ''
  const chunks: SpeechChunk[] = []
  let startTs = 0

  if (recognition) {
    recognition.lang = opts.lang || 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = true

    recognition.onstart = () => {
      recording = true
      startTs = Date.now()
    }
    recognition.onresult = (event) => {
      // 记录每次识别片段的相对时间戳，用于停顿/语速分析
      const ts = Date.now() - startTs
      let interim = ''
      let finalAdded = ''
      for (let i = 0; i < event.results.length; i++) {
        const r = event.results[i]
        const t = r[0]?.transcript ?? ''
        if (r.isFinal) {
          finalAdded += t
        } else {
          interim += t
        }
      }
      chunks.push({ ts, text: (finalAdded || interim).trim() })
      if (finalAdded) finalized += finalAdded
      opts.onFinalText?.(correctTechTerms(finalized))
    }
    recognition.onend = () => {
      if (!recording) return
      recording = false
      opts.onMetrics?.(computeSpeechMetrics(chunks, correctTechTerms(finalized)))
    }
    recognition.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        opts.onError?.('麦克风或语音服务未授权，请授权后重试')
      } else if (e.error === 'network') {
        opts.onError?.('语音识别服务网络异常，请检查网络后重试')
      } else if (e.error === 'no-speech') {
        opts.onError?.('未检测到语音，请对准麦克风说话')
      } else {
        opts.onError?.(`语音识别出错：${e.error}`)
      }
    }
  }

  return {
    supported,
    isRecording: () => recording,
    start(): boolean {
      if (!recognition) return false
      if (recording) return true
      finalized = ''
      chunks.length = 0
      try {
        recognition.start()
        return true
      } catch {
        return false
      }
    },
    stop() {
      if (!recognition || !recording) return
      recognition.stop()
    },
    /** 主动终止（不触发指标回调的硬停止） */
    cancel() {
      if (!recognition || !recording) return
      recording = false
      recognition.abort()
    },
  }
}
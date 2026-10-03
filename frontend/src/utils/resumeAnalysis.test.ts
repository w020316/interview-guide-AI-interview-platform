import { describe, it, expect } from 'vitest'
import { isParseFailed, AI_PARSE_FAILED } from './resumeAnalysis'

/**
 * 回归测试：锁定 P0「简历解析失败被伪装成 0 分」缺陷。
 * 用例尽量还原**原缺陷的输入形状**（含线上真实兜底串）。
 */

/** 线上真实兜底串（id=17 的原文） */
const LEGACY_FALLBACK = JSON.stringify({
  strengths: [],
  dimensions: [],
  improvements: ['AI 返回内容无法解析，请稍后重试'],
  overallScore: 0,
})

/** 后端修复后的新兜底串 */
const NEW_FALLBACK = JSON.stringify({
  strengths: [],
  dimensions: [],
  improvements: ['AI 返回内容无法解析，请稍后重试'],
  overallScore: null,
  error: AI_PARSE_FAILED,
})

/** 正常分析结果（id=18 形状） */
const NORMAL = JSON.stringify({
  strengths: ['项目经验扎实'],
  dimensions: [
    { name: '完整性', score: 80, suggestion: '补充量化指标' },
    { name: '准确性', score: 76, suggestion: '技术栈表述更精确' },
    { name: '表达力', score: 78, suggestion: '精简冗长句' },
  ],
  improvements: ['增加项目量化结果'],
  overallScore: 78,
})

describe('isParseFailed', () => {
  it('新形状（error=AI_PARSE_FAILED + overallScore=null）判为失败', () => {
    expect(isParseFailed({ overallScore: null, analysisResult: NEW_FALLBACK })).toBe(true)
  })

  it('新形状（顶层 error 字段）也判为失败', () => {
    expect(isParseFailed({ error: AI_PARSE_FAILED, overallScore: null, analysisResult: NORMAL })).toBe(true)
  })

  it('历史脏数据（overallScore=0 + 兜底 improvements）判为失败', () => {
    expect(isParseFailed({ overallScore: 0, analysisResult: LEGACY_FALLBACK })).toBe(true)
  })

  it('正常分析结果不判为失败', () => {
    expect(isParseFailed({ overallScore: 78, analysisResult: NORMAL })).toBe(false)
  })

  it('真实 0 分（有维度明细）不判为失败', () => {
    const realZero = JSON.stringify({
      strengths: ['有一定基础'],
      dimensions: [
        { name: '完整性', score: 0, suggestion: '内容严重缺失' },
        { name: '准确性', score: 0, suggestion: '存在事实错误' },
      ],
      improvements: ['系统性地重写简历'],
      overallScore: 0,
    })
    expect(isParseFailed({ overallScore: 0, analysisResult: realZero })).toBe(false)
  })

  it('analysisResult 非合法 JSON 判为失败', () => {
    expect(isParseFailed({ overallScore: null, analysisResult: '{broken json' })).toBe(true)
  })

  it('analysisResult 缺失/为空判为失败（避免渲染空白详情）', () => {
    expect(isParseFailed({ overallScore: null })).toBe(true)
    expect(isParseFailed({ overallScore: null, analysisResult: '' })).toBe(true)
    expect(isParseFailed({ overallScore: null, analysisResult: '   ' })).toBe(true)
  })

  it('空对象/空记录不抛异常且不判为失败', () => {
    expect(isParseFailed(null)).toBe(false)
    expect(isParseFailed(undefined)).toBe(false)
    expect(isParseFailed({})).toBe(true) // 无 analysisResult → 失败（上面用例语义一致）
  })

  it('dimensions 为空但 improvements 是真实建议时不算失败', () => {
    const partial = JSON.stringify({
      strengths: [],
      dimensions: [],
      improvements: ['建议补充项目量化指标'],
      overallScore: 55,
    })
    expect(isParseFailed({ overallScore: 55, analysisResult: partial })).toBe(false)
  })
})

import { describe, it, expect } from 'vitest'
import {
  getScoreColor,
  getScoreGradient,
  DEFAULT_THRESHOLDS,
  MATCH_THRESHOLDS,
} from './score'

describe('score utils', () => {
  describe('getScoreColor - 默认阈值 85/70/60', () => {
    it('null/undefined 返回中性灰', () => {
      expect(getScoreColor(null)).toBe('var(--c-text-tertiary)')
      expect(getScoreColor(undefined)).toBe('var(--c-text-tertiary)')
    })

    it('>=85 返回优秀色（绿）', () => {
      expect(getScoreColor(85)).toBe('var(--score-excellent)')
      expect(getScoreColor(100)).toBe('var(--score-excellent)')
    })

    it('70-84 返回良好色（品牌墨绿，非蓝）', () => {
      expect(getScoreColor(70)).toBe('var(--score-good)')
      expect(getScoreColor(84)).toBe('var(--score-good)')
    })

    it('60-69 返回及格色（琥珀）', () => {
      expect(getScoreColor(60)).toBe('var(--score-pass)')
      expect(getScoreColor(69)).toBe('var(--score-pass)')
    })

    it('<60 返回不及格色（红）', () => {
      expect(getScoreColor(59)).toBe('var(--score-fail)')
      expect(getScoreColor(0)).toBe('var(--score-fail)')
    })
  })

  describe('getScoreColor - 匹配度阈值 80/60/40', () => {
    it('>=80 返回优秀色', () => {
      expect(getScoreColor(80, MATCH_THRESHOLDS)).toBe('var(--score-excellent)')
      expect(getScoreColor(100, MATCH_THRESHOLDS)).toBe('var(--score-excellent)')
    })

    it('60-79 返回良好色', () => {
      expect(getScoreColor(60, MATCH_THRESHOLDS)).toBe('var(--score-good)')
      expect(getScoreColor(79, MATCH_THRESHOLDS)).toBe('var(--score-good)')
    })

    it('40-59 返回及格色', () => {
      expect(getScoreColor(40, MATCH_THRESHOLDS)).toBe('var(--score-pass)')
      expect(getScoreColor(59, MATCH_THRESHOLDS)).toBe('var(--score-pass)')
    })

    it('<40 返回不及格色', () => {
      expect(getScoreColor(39, MATCH_THRESHOLDS)).toBe('var(--score-fail)')
      expect(getScoreColor(0, MATCH_THRESHOLDS)).toBe('var(--score-fail)')
    })

    it('null 仍返回中性灰', () => {
      expect(getScoreColor(null, MATCH_THRESHOLDS)).toBe('var(--c-text-tertiary)')
    })
  })

  describe('getScoreGradient', () => {
    it('优秀返回绿色渐变（令牌）', () => {
      expect(getScoreGradient(90)).toContain('var(--score-excellent)')
    })

    it('不及格返回红色渐变（令牌）', () => {
      expect(getScoreGradient(30)).toContain('var(--score-fail)')
    })

    it('支持自定义阈值（50 命中及格档）', () => {
      expect(getScoreGradient(50, MATCH_THRESHOLDS)).toContain('var(--score-pass)')
    })
  })

  describe('阈值常量', () => {
    it('DEFAULT_THRESHOLDS 为 85/70/60', () => {
      expect(DEFAULT_THRESHOLDS).toEqual({ excellent: 85, good: 70, pass: 60 })
    })

    it('MATCH_THRESHOLDS 为 80/60/40', () => {
      expect(MATCH_THRESHOLDS).toEqual({ excellent: 80, good: 60, pass: 40 })
    })
  })
})

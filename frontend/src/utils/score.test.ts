import { describe, it, expect } from 'vitest'
import {
  getScoreColor,
  getScoreGradient,
  getScoreBg,
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
      expect(getScoreColor(85)).toBe('#10b981')
      expect(getScoreColor(100)).toBe('#10b981')
    })

    it('70-84 返回良好色（蓝）', () => {
      expect(getScoreColor(70)).toBe('#3b82f6')
      expect(getScoreColor(84)).toBe('#3b82f6')
    })

    it('60-69 返回及格色（橙）', () => {
      expect(getScoreColor(60)).toBe('#f59e0b')
      expect(getScoreColor(69)).toBe('#f59e0b')
    })

    it('<60 返回不及格色（红）', () => {
      expect(getScoreColor(59)).toBe('#ef4444')
      expect(getScoreColor(0)).toBe('#ef4444')
    })
  })

  describe('getScoreColor - 匹配度阈值 80/60/40', () => {
    it('>=80 返回优秀色', () => {
      expect(getScoreColor(80, MATCH_THRESHOLDS)).toBe('#10b981')
      expect(getScoreColor(100, MATCH_THRESHOLDS)).toBe('#10b981')
    })

    it('60-79 返回良好色', () => {
      expect(getScoreColor(60, MATCH_THRESHOLDS)).toBe('#3b82f6')
      expect(getScoreColor(79, MATCH_THRESHOLDS)).toBe('#3b82f6')
    })

    it('40-59 返回及格色', () => {
      expect(getScoreColor(40, MATCH_THRESHOLDS)).toBe('#f59e0b')
      expect(getScoreColor(59, MATCH_THRESHOLDS)).toBe('#f59e0b')
    })

    it('<40 返回不及格色', () => {
      expect(getScoreColor(39, MATCH_THRESHOLDS)).toBe('#ef4444')
      expect(getScoreColor(0, MATCH_THRESHOLDS)).toBe('#ef4444')
    })

    it('null 仍返回中性灰', () => {
      expect(getScoreColor(null, MATCH_THRESHOLDS)).toBe('var(--c-text-tertiary)')
    })
  })

  describe('getScoreGradient', () => {
    it('优秀返回绿色渐变', () => {
      expect(getScoreGradient(90)).toBe('linear-gradient(90deg, #10b981, #34d399)')
    })

    it('不及格返回红色渐变', () => {
      expect(getScoreGradient(30)).toBe('linear-gradient(90deg, #ef4444, #f87171)')
    })

    it('支持自定义阈值', () => {
      expect(getScoreGradient(50, MATCH_THRESHOLDS)).toBe('linear-gradient(90deg, #f59e0b, #fbbf24)')
    })
  })

  describe('getScoreBg', () => {
    it('优秀返回浅绿底', () => {
      expect(getScoreBg(90)).toBe('#ecfdf5')
    })

    it('不及格返回浅红底', () => {
      expect(getScoreBg(30)).toBe('#fef2f2')
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

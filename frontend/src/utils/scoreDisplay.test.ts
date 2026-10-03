import { describe, it, expect } from 'vitest'
import {
  hasScoreSample,
  scoreText,
  scoreTextWithUnit,
  scoreColor,
  scoreFill,
  NEUTRAL_SCORE_COLOR,
  NEUTRAL_SCORE_FILL,
} from './scoreDisplay'
import { EMPTY } from './format'

/**
 * 回归测试：锁定 P0「未作答被渲染成 0 分」缺陷。
 * 关键：`answered === 0`（历史形状 avgScore=0）与 `avgScore === null`（新形状）
 * 都必须视为「无数据」→ 显示「—」+ 中性色，绝不出现「0 分」或危险红。
 */
describe('scoreDisplay', () => {
  describe('无数据（answered=0）', () => {
    it('历史形状 answered=0 且 avgScore=0 → 无样本', () => {
      expect(hasScoreSample({ answered: 0, avgScore: 0 })).toBe(false)
    })

    it('新形状 answered=0 且 avgScore=null → 无样本', () => {
      expect(hasScoreSample({ answered: 0, avgScore: null })).toBe(false)
    })

    it('渲染「—」而不是「0 分」', () => {
      expect(scoreText({ answered: 0, avgScore: 0 })).toBe(EMPTY)
      expect(scoreTextWithUnit({ answered: 0, avgScore: 0 })).toBe(EMPTY)
      expect(scoreTextWithUnit({ answered: 0, avgScore: null })).toBe(EMPTY)
    })

    it('颜色为中性占位色，且不是危险红', () => {
      const color = scoreColor({ answered: 0, avgScore: 0 })
      expect(color).toBe(NEUTRAL_SCORE_COLOR)
      expect(color).not.toBe('var(--score-fail)')
      expect(scoreFill({ answered: 0, avgScore: 0 })).toBe(NEUTRAL_SCORE_FILL)
    })
  })

  describe('有数据（answered>0）', () => {
    it('answered=2, avgScore=78 → 渲染「78 分」', () => {
      expect(hasScoreSample({ answered: 2, avgScore: 78 })).toBe(true)
      expect(scoreTextWithUnit({ answered: 2, avgScore: 78 })).toBe('78 分')
      expect(scoreText({ answered: 2, avgScore: 78 })).toBe('78')
    })

    it('answered>0 时按分数返回对应色阶（78 → 良好色）', () => {
      expect(scoreColor({ answered: 2, avgScore: 78 })).toBe('var(--score-good)')
    })

    it('answered>0 但 avgScore 缺失（异常数据）仍视为无样本', () => {
      expect(hasScoreSample({ answered: 3, avgScore: null })).toBe(false)
      expect(scoreTextWithUnit({ answered: 3, avgScore: null })).toBe(EMPTY)
    })
  })
})

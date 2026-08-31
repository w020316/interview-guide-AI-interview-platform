import { describe, it, expect } from 'vitest'
import { JOB_SUGGESTIONS } from './jobOptions'

describe('utils/jobOptions', () => {
  it('导出非空的高频岗位列表', () => {
    expect(Array.isArray(JOB_SUGGESTIONS)).toBe(true)
    expect(JOB_SUGGESTIONS.length).toBeGreaterThan(40)
  })

  it('每一项均为非空字符串', () => {
    for (const job of JOB_SUGGESTIONS) {
      expect(typeof job).toBe('string')
      expect(job.trim().length).toBeGreaterThan(0)
    }
  })

  it('包含用户画像对应的技术类岗位', () => {
    expect(JOB_SUGGESTIONS).toContain('产品经理')
    expect(JOB_SUGGESTIONS).toContain('Java 后端开发工程师')
    expect(JOB_SUGGESTIONS).toContain('前端开发工程师')
  })

  it('覆盖多行业岗位（医疗/法律/财会/餐饮）', () => {
    expect(JOB_SUGGESTIONS).toContain('医生')
    expect(JOB_SUGGESTIONS).toContain('律师')
    expect(JOB_SUGGESTIONS).toContain('会计师')
    expect(JOB_SUGGESTIONS).toContain('厨师')
  })

  it('无重复项', () => {
    const set = new Set(JOB_SUGGESTIONS)
    expect(set.size).toBe(JOB_SUGGESTIONS.length)
  })
})
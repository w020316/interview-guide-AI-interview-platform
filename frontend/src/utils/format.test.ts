import { describe, it, expect } from 'vitest'
import { EMPTY, formatEmpty } from './format'

describe('format utils', () => {
  it('EMPTY 为全站统一占位符「—」', () => {
    expect(EMPTY).toBe('—')
  })

  it('null / undefined / 空串 → 占位符', () => {
    expect(formatEmpty(null)).toBe(EMPTY)
    expect(formatEmpty(undefined)).toBe(EMPTY)
    expect(formatEmpty('')).toBe(EMPTY)
    expect(formatEmpty('   ')).toBe(EMPTY)
  })

  it('数字 0 是有效值，不替换为占位符', () => {
    expect(formatEmpty(0)).toBe('0')
  })

  it('其余值原样字符串化', () => {
    expect(formatEmpty(78)).toBe('78')
    expect(formatEmpty('Java')).toBe('Java')
    expect(formatEmpty(false)).toBe('false')
  })
})

import { describe, it, expect } from 'vitest'
import { formatDate, formatDateOnly } from './format'

describe('format utils', () => {
  describe('formatDate', () => {
    it('null/undefined/空串返回 —', () => {
      expect(formatDate(null)).toBe('—')
      expect(formatDate(undefined)).toBe('—')
      expect(formatDate('')).toBe('—')
    })

    it('非法日期返回 —', () => {
      expect(formatDate('not-a-date')).toBe('—')
    })

    it('时间戳格式化为 YYYY-MM-DD HH:mm', () => {
      const ts = new Date(2026, 6, 25, 14, 30).getTime()
      const result = formatDate(ts)
      expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
      expect(result).toBe('2026-07-25 14:30')
    })

    it('ISO 字符串格式化', () => {
      const result = formatDate('2026-07-25T14:30:00')
      expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
    })

    it('补零', () => {
      const ts = new Date(2026, 0, 5, 9, 5).getTime()
      expect(formatDate(ts)).toBe('2026-01-05 09:05')
    })
  })

  describe('formatDateOnly', () => {
    it('null 返回 —', () => {
      expect(formatDateOnly(null)).toBe('—')
    })

    it('仅返回日期部分 YYYY-MM-DD', () => {
      const ts = new Date(2026, 6, 25, 14, 30).getTime()
      expect(formatDateOnly(ts)).toBe('2026-07-25')
    })
  })
})

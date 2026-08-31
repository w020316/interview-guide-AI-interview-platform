import { describe, it, expect } from 'vitest'
import {
  WEEKDAY_LABELS, dayDiff, fmtDateTime, isSameDate, monthMatrix,
  parseDate, statusText, statusVariant, toDateString, toDatetimeLocal,
} from './calendar'

describe('calendar utils', () => {
  describe('toDateString', () => {
    it('本地时区零填充为 yyyy-MM-dd', () => {
      expect(toDateString(new Date(2026, 0, 5))).toBe('2026-01-05')
      expect(toDateString(new Date(2026, 11, 31))).toBe('2026-12-31')
    })
  })

  describe('monthMatrix', () => {
    it('输出固定 42 格', () => {
      expect(monthMatrix(2026, 8).length).toBe(42)
    })

    it('本月内的日期 inMonth 为 true', () => {
      const cells = monthMatrix(2026, 8) // 2026-09
      const inMonth = cells.filter((c) => c.inMonth)
      expect(inMonth.length).toBe(30) // 9 月 30 天
    })

    it('日期连续且排序正确', () => {
      const cells = monthMatrix(2026, 8)
      for (let i = 1; i < cells.length; i++) {
        const prev = parseDate(cells[i - 1].date)
        const cur = parseDate(cells[i].date)
        expect(cur.getTime() - prev.getTime()).toBe(86400000)
      }
    })

    it('isToday 标记今天', () => {
      const today = toDateString(new Date())
      const cells = monthMatrix(new Date().getFullYear(), new Date().getMonth())
      expect(cells.find((c) => c.isToday)?.date).toBe(today)
    })
  })

  describe('isSameDate', () => {
    it('相同日期为真，否则为假', () => {
      expect(isSameDate('2026-09-05', '2026-09-05')).toBe(true)
      expect(isSameDate('2026-09-05', '2026-09-06')).toBe(false)
    })
  })

  describe('parseDate', () => {
    it('解析 yyyy-MM-dd 为本地日期', () => {
      const d = parseDate('2026-09-05')
      expect(d.getFullYear()).toBe(2026)
      expect(d.getMonth()).toBe(8)
      expect(d.getDate()).toBe(5)
    })
  })

  describe('toDatetimeLocal', () => {
    it('输出 yyyy-MM-ddTHH:mm（补零）', () => {
      expect(toDatetimeLocal(new Date(2026, 8, 5, 9, 5))).toBe('2026-09-05T09:05')
    })
  })

  describe('fmtDateTime', () => {
    it('空值返回空串', () => {
      expect(fmtDateTime(null)).toBe('')
      expect(fmtDateTime(undefined)).toBe('')
    })

    it('非法日期原样返回', () => {
      expect(fmtDateTime('not-a-date')).toBe('not-a-date')
    })

    it('今天显示 今天 HH:mm', () => {
      const now = new Date()
      const iso = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 14, 30).toString()
      expect(fmtDateTime(iso)).toContain('今天 14:30')
    })

    it('非今日显示 MM-DD HH:mm', () => {
      const d = new Date(2026, 8, 15, 10, 0)
      const iso = d.toString()
      expect(fmtDateTime(iso)).toBe('09-15 10:00')
    })
  })

  describe('dayDiff', () => {
    it('今天为 0，未来为正，过去为负', () => {
      const today = toDateString(new Date())
      expect(dayDiff(today)).toBe(0)
      const past = parseDate(today)
      past.setDate(past.getDate() - 1)
      expect(dayDiff(toDateString(past))).toBe(-1)
      const future = parseDate(today)
      future.setDate(future.getDate() + 3)
      expect(dayDiff(toDateString(future))).toBe(3)
    })
  })

  describe('statusText / statusVariant', () => {
    it('状态文案映射', () => {
      expect(statusText('DONE')).toBe('已完成')
      expect(statusText('CANCELLED')).toBe('已取消')
      expect(statusText('UPCOMING')).toBe('待面试')
      expect(statusText(null)).toBe('待面试')
    })

    it('状态变体映射', () => {
      expect(statusVariant('DONE')).toBe('success')
      expect(statusVariant('CANCELLED')).toBe('danger')
      expect(statusVariant('UPCOMING')).toBe('warning')
    })
  })

  it('WEEKDAY_LABELS 为周日开头 7 项', () => {
    expect(WEEKDAY_LABELS).toEqual(['日', '一', '二', '三', '四', '五', '六'])
    expect(WEEKDAY_LABELS.length).toBe(7)
  })
})
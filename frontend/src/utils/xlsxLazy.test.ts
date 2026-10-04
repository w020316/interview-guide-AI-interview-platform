import { describe, it, expect, vi, beforeEach } from 'vitest'

/** 用 hoisted 变量做 mock，保证 vi.mock 工厂可安全引用 */
const { readXlsxMock } = vi.hoisted(() => ({ readXlsxMock: vi.fn() }))
vi.mock('read-excel-file', () => ({ default: readXlsxMock }))

import { parseXlsxRows, cellToString, normalizeXlsxRows, formatDateCell, XLSX_READ_ERROR_HINT } from './xlsxLazy'

/**
 * Excel 懒加载解析（第三批 F）单测。
 *
 * 说明：`read-excel-file` 被 mock，只验证「动态 import + 归一化 + 错误透传」的逻辑；
 * 「主包不含 read-excel-file」由构建产物检查保证（见交付说明）。
 */
describe('cellToString / normalizeXlsxRows', () => {
  it('null/undefined → 空串；布尔 → TRUE/FALSE；数字 0 保留为 "0"', () => {
    expect(cellToString(null)).toBe('')
    expect(cellToString(undefined)).toBe('')
    expect(cellToString(true)).toBe('TRUE')
    expect(cellToString(false)).toBe('FALSE')
    expect(cellToString(0)).toBe('0')
    expect(cellToString('字节')).toBe('字节')
  })

  it('日期单元格格式化为 yyyy-MM-dd（本地时区）', () => {
    const d = new Date(2026, 8, 5) // 2026-09-05
    expect(formatDateCell(d)).toBe('2026-09-05')
    expect(cellToString(d)).toBe('2026-09-05')
  })

  it('normalizeXlsxRows 逐格转换', () => {
    const rows = normalizeXlsxRows([[null, undefined, 0, false]])
    expect(rows).toEqual([['', '', '0', 'FALSE']])
  })
})

describe('parseXlsxRows', () => {
  beforeEach(() => readXlsxMock.mockReset())

  it('动态 import 解析出的网格并归一化为字符串', async () => {
    readXlsxMock.mockResolvedValueOnce([
      ['公司', '岗位'],
      ['字节跳动', '后端工程师'],
      [1, true],
    ])
    const rows = await parseXlsxRows(new File(['x'], 'jobs.xlsx'))
    expect(readXlsxMock).toHaveBeenCalledTimes(1)
    expect(rows).toEqual([['公司', '岗位'], ['字节跳动', '后端工程师'], ['1', 'TRUE']])
  })

  it('读取失败时抛错（由调用方展示 XLSX_READ_ERROR_HINT）', async () => {
    readXlsxMock.mockRejectedValueOnce(new Error('无法解析'))
    await expect(parseXlsxRows(new File(['x'], 'old.xls'))).rejects.toThrow('无法解析')
    expect(XLSX_READ_ERROR_HINT).toContain('另存为 CSV')
  })
})

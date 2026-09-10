import { describe, it, expect } from 'vitest'
import { scoreLevel, scoreHex, shareFileName, todayText } from './reportShare'

describe('reportShare 分享卡片纯逻辑', () => {
  it('scoreLevel: 分数等级分界与 reportPdf 口径一致', () => {
    expect(scoreLevel(95)).toBe('优秀')
    expect(scoreLevel(85)).toBe('优秀')
    expect(scoreLevel(84)).toBe('良好')
    expect(scoreLevel(70)).toBe('良好')
    expect(scoreLevel(69)).toBe('合格')
    expect(scoreLevel(59)).toBe('待加强')
  })

  it('scoreHex: 各等级返回不重复色值', () => {
    const hexes = [scoreHex(90), scoreHex(75), scoreHex(65), scoreHex(40)]
    expect(new Set(hexes).size).toBe(4)
    expect(hexes.every((h) => /^#[0-9a-f]{6}$/.test(h))).toBe(true)
  })

  it('shareFileName: 清洗非法文件字符并截断岗位名', () => {
    expect(shareFileName('Java 后端/工程师:2026', '2026-09-10')).toBe('share_Java后端工程师2026_2026-09-10.png')
  })

  it('shareFileName: 空岗位降级为默认名', () => {
    expect(shareFileName('', '2026-09-10')).toBe('share_面试报告_2026-09-10.png')
    expect(shareFileName('///', '2026-09-10')).toBe('share_面试报告_2026-09-10.png')
  })

  it('todayText: 输出 YYYY-MM-DD 且补零', () => {
    expect(todayText()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

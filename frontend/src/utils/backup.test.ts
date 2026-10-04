import { describe, it, expect } from 'vitest'
import {
  BACKUP_SCHEMA_VERSION,
  IMPORT_MODES,
  exportFileName,
  collectionStats,
  fingerprintSource,
  describeImportSummary,
  isFingerprintMismatch,
  isFingerprintConflict,
  parseBackupFile,
} from './backup'

/**
 * 个人数据导出/导入（第三批 C）单测。
 *
 * 重点：规范化指纹字符串必须与后端 fingerprintOf 的输入**同构**（含各集合所用时间戳字段），
 * 以及备份文件的严格校验。
 */
describe('exportFileName', () => {
  it('生成带时间戳的 .json 文件名', () => {
    expect(exportFileName(new Date(2026, 9, 3, 9, 5, 7))).toBe(
      'interview-guide-backup-20261003-090507.json',
    )
  })

  it('个位数月/日/时/分/秒补零', () => {
    expect(exportFileName(new Date(2026, 0, 2, 3, 4, 5))).toBe(
      'interview-guide-backup-20260102-030405.json',
    )
  })
})

describe('collectionStats', () => {
  it('统计各集合数量，questions 由 sessions[].questions 汇总', () => {
    const stats = collectionStats({
      resumes: [{}, {}],
      sessions: [{ questions: [{}, {}] }, { questions: [{}] }],
      applications: [{}],
      events: [],
      favoriteQuestions: [{}],
      storyBank: [{}, {}, {}],
      jobFavorites: [{}],
    })
    expect(stats).toEqual({
      resumes: 2,
      sessions: 2,
      questions: 3,
      applications: 1,
      events: 0,
      favoriteQuestions: 1,
      storyBank: 3,
      jobFavorites: 1,
    })
  })

  it('空/非数组输入一律计 0', () => {
    expect(collectionStats(null)).toEqual({
      resumes: 0, sessions: 0, questions: 0, applications: 0,
      events: 0, favoriteQuestions: 0, storyBank: 0, jobFavorites: 0,
    })
    expect(collectionStats({ resumes: 'oops' as unknown as never }).resumes).toBe(0)
  })
})

describe('fingerprintSource（与后端同构）', () => {
  it('空数据：每段为 <name>:0:-;（顺序与后端一致）', () => {
    expect(fingerprintSource({})).toBe(
      'resumes:0:-;sessions:0:-;applications:0:-;events:0:-;favoriteQuestions:0:-;storyBank:0:-;jobFavorites:0:-;',
    )
  })

  it('取各集合计数与最大时间戳', () => {
    const src = fingerprintSource({
      resumes: [{ createdAt: '2026-09-01T10:00:00' }, { createdAt: '2026-09-02T09:00:00' }],
      applications: [{ updatedAt: '2026-10-01T08:00:00' }],
    })
    expect(src).toBe(
      'resumes:2:2026-09-02T09:00:00;' +
        'sessions:0:-;' +
        'applications:1:2026-10-01T08:00:00;' +
        'events:0:-;' +
        'favoriteQuestions:0:-;' +
        'storyBank:0:-;' +
        'jobFavorites:0:-;',
    )
  })

  it('applications/storyBank 用 updatedAt，其余用 createdAt', () => {
    const src = fingerprintSource({
      applications: [{ createdAt: '1999-01-01T00:00:00', updatedAt: '2026-10-01T08:00:00' }],
      storyBank: [{ createdAt: '1999-01-01T00:00:00', updatedAt: '2026-10-02T08:00:00' }],
    })
    expect(src).toContain('applications:1:2026-10-01T08:00:00;')
    expect(src).toContain('storyBank:1:2026-10-02T08:00:00;')
  })

  it('计数变化会改变规范化字符串（可用于本地预校验）', () => {
    const a = fingerprintSource({ resumes: [{}] })
    const b = fingerprintSource({ resumes: [{}, {}] })
    expect(a).not.toBe(b)
  })

  it('时间戳缺失时该段为 "-"', () => {
    expect(fingerprintSource({ resumes: [{}, {}] })).toContain('resumes:2:-;')
  })
})

describe('摘要 / 冲突判定', () => {
  it('describeImportSummary 缺省按 0（真实计数，不臆造）', () => {
    expect(describeImportSummary({ added: 2, merged: 1 })).toEqual(['新增 2', '合并 1', '跳过 0', '警告 0'])
    expect(describeImportSummary(null)).toEqual(['新增 0', '合并 0', '跳过 0', '警告 0'])
  })

  it('isFingerprintMismatch：两者都有值且不同才为 true', () => {
    expect(isFingerprintMismatch('a', 'b')).toBe(true)
    expect(isFingerprintMismatch('a', 'a')).toBe(false)
    expect(isFingerprintMismatch('', 'b')).toBe(false)
    expect(isFingerprintMismatch(undefined, 'b')).toBe(false)
  })

  it('isFingerprintConflict 只认 HTTP 409', () => {
    expect(isFingerprintConflict({ response: { status: 409 } })).toBe(true)
    expect(isFingerprintConflict({ response: { status: 400 } })).toBe(false)
    expect(isFingerprintConflict(new Error('x'))).toBe(false)
    expect(isFingerprintConflict(null)).toBe(false)
  })

  it('导入模式含合并与覆盖', () => {
    expect(IMPORT_MODES.map((m) => m.value)).toEqual(['merge', 'replace'])
    expect(BACKUP_SCHEMA_VERSION).toBe('3')
  })
})

describe('parseBackupFile', () => {
  it('解析合法备份（含 data）', () => {
    const payload = parseBackupFile(JSON.stringify({ schemaVersion: '3', data: { resumes: [] } }))
    expect(payload.data).toEqual({ resumes: [] })
  })

  it('非法 JSON 抛可读错误', () => {
    expect(() => parseBackupFile('{ not json')).toThrow('文件不是有效的 JSON')
  })

  it('顶层非对象抛错', () => {
    expect(() => parseBackupFile('[]')).toThrow('顶层应为对象')
    expect(() => parseBackupFile('123')).toThrow('顶层应为对象')
  })

  it('缺少 data 字段抛错', () => {
    expect(() => parseBackupFile('{"schemaVersion":"3"}')).toThrow('缺少 data 字段')
    expect(() => parseBackupFile('{"data":[]}')).toThrow('缺少 data 字段')
  })
})

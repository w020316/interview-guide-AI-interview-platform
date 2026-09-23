import { describe, it, expect } from 'vitest'
import { RECRUIT_TYPE_CODES, RECRUIT_TYPE_LABELS, recruitTypeLabel } from './recruitType'

describe('utils/recruitType', () => {
  it('后端所有招聘类型取值都有中文标签（漏配会在这里挂掉）', () => {
    const missing = RECRUIT_TYPE_CODES.filter((c) => !RECRUIT_TYPE_LABELS[c])
    expect(missing, `缺少标签：${missing.join(', ')}`).toEqual([])
  })

  it('PART_TIME 显示为「兼职」（曾经漏配，管理后台里显示原始英文枚举）', () => {
    expect(recruitTypeLabel('PART_TIME')).toBe('兼职')
  })

  it('常见取值映射正确', () => {
    expect(recruitTypeLabel('AUTUMN')).toBe('秋招')
    expect(recruitTypeLabel('SPRING')).toBe('春招')
    expect(recruitTypeLabel('SOCIAL')).toBe('社招')
    expect(recruitTypeLabel('INTERN')).toBe('实习')
    expect(recruitTypeLabel('TARGETED')).toBe('定向')
  })

  it('未知取值回退为原值（便于在界面上一眼发现漏配，而不是显示空白）', () => {
    expect(recruitTypeLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE')
  })

  it('空值显示为「—」', () => {
    expect(recruitTypeLabel(null)).toBe('—')
    expect(recruitTypeLabel(undefined)).toBe('—')
    expect(recruitTypeLabel('')).toBe('—')
  })
})

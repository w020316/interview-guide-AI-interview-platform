import { describe, it, expect } from 'vitest'
import {
  RECRUIT_TYPE_CODES,
  RECRUIT_TYPE_LABELS,
  recruitTypeLabel,
  toQueryRecruitType,
} from './recruitType'

describe('utils/recruitType', () => {
  it('后端所有招聘类型取值都有中文标签（漏配会在这里挂掉）', () => {
    const missing = RECRUIT_TYPE_CODES.filter((c) => !RECRUIT_TYPE_LABELS[c])
    expect(missing, `缺少标签：${missing.join(', ')}`).toEqual([])
  })

  it('RECRUIT_TYPE_CODES 与 RECRUIT_TYPE_LABELS 一一对应（无遗漏、无多余、无重复）', () => {
    const codes = [...RECRUIT_TYPE_CODES]
    // 无重复
    expect(new Set(codes).size).toBe(codes.length)
    // 键集合完全一致：既防「有 code 没标签」，也防「有标签没 code」
    expect(codes.slice().sort()).toEqual(Object.keys(RECRUIT_TYPE_LABELS).slice().sort())
  })

  it('PART_TIME 显示为「兼职」（曾经漏配，管理后台里显示原始英文枚举）', () => {
    expect(recruitTypeLabel('PART_TIME')).toBe('兼职')
  })

  it('常见取值映射正确', () => {
    expect(recruitTypeLabel('AUTUMN')).toBe('秋招')
    expect(recruitTypeLabel('SPRING')).toBe('春招')
    expect(recruitTypeLabel('SOCIAL')).toBe('社招')
    expect(recruitTypeLabel('INTERN')).toBe('实习')
    expect(recruitTypeLabel('TARGETED')).toBe('定向专项')
  })

  it('未知取值回退为原值（便于在界面上一眼发现漏配，而不是显示空白）', () => {
    expect(recruitTypeLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE')
  })

  it('空值显示为「—」', () => {
    expect(recruitTypeLabel(null)).toBe('—')
    expect(recruitTypeLabel(undefined)).toBe('—')
    expect(recruitTypeLabel('')).toBe('—')
  })

  it('toQueryRecruitType: 真实招聘类型原样透出', () => {
    expect(toQueryRecruitType('AUTUMN')).toBe('AUTUMN')
    expect(toQueryRecruitType('SPRING')).toBe('SPRING')
    expect(toQueryRecruitType('PART_TIME')).toBe('PART_TIME')
    expect(toQueryRecruitType('TARGETED')).toBe('TARGETED')
  })

  it('toQueryRecruitType: 虚拟分栏值（空串 / FAVORITE / OVERSEAS）不外泄为 recruitType', () => {
    // 这三个值都不是真实招聘类型，若传给后端会被严格校验判成 400（P3-03 潜在回归）
    expect(toQueryRecruitType('')).toBeUndefined()
    expect(toQueryRecruitType('FAVORITE')).toBeUndefined()
    expect(toQueryRecruitType('OVERSEAS')).toBeUndefined()
    // 空值同样视为「不改写参数」
    expect(toQueryRecruitType(null)).toBeUndefined()
    expect(toQueryRecruitType(undefined)).toBeUndefined()
  })
})

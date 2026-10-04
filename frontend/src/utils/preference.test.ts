import { describe, it, expect } from 'vitest'
import {
  normalizePreference,
  preferenceLabel,
  preferenceVariant,
  matchesFilter,
  preferenceCounts,
  canCompare,
  toggleSelection,
  PREFERENCE_UNSET_LABEL,
  PREFERENCE_FILTERS,
  COMPARE_MAX,
} from './preference'

/**
 * H 岗位偏好四档纯函数单测。
 * 重点：`null` = 未标记，**不得默认成任何一档**；筛选「全部」含未标记。
 */
describe('preference · 归一化与标签', () => {
  it('合法档位原样返回（大小写归一）', () => {
    expect(normalizePreference('STRONG')).toBe('STRONG')
    expect(normalizePreference('acceptable')).toBe('ACCEPTABLE')
    expect(normalizePreference(' Backup ')).toBe('BACKUP')
    expect(normalizePreference('EXCLUDED')).toBe('EXCLUDED')
  })

  it('null / undefined / 空串 / 非法值 → null（未标记，不默认成任何一档）', () => {
    expect(normalizePreference(null)).toBeNull()
    expect(normalizePreference(undefined)).toBeNull()
    expect(normalizePreference('')).toBeNull()
    expect(normalizePreference('  ')).toBeNull()
    expect(normalizePreference('VERY_HIGH')).toBeNull()
    expect(normalizePreference(3)).toBeNull()
    expect(normalizePreference({})).toBeNull()
  })

  it('标签：未标记显示「未标记」，不显示任何档位', () => {
    expect(preferenceLabel(null)).toBe(PREFERENCE_UNSET_LABEL)
    expect(preferenceLabel('STRONG')).toBe('强烈意向')
    expect(preferenceLabel('EXCLUDED')).toBe('不考虑')
    expect(preferenceVariant(null)).toBe('info')
    expect(preferenceVariant('STRONG')).toBe('success')
  })
})

describe('preference · 筛选', () => {
  it('ALL 命中一切（含未标记）', () => {
    expect(matchesFilter(null, 'ALL')).toBe(true)
    expect(matchesFilter('STRONG', 'ALL')).toBe(true)
  })

  it('UNSET 仅命中未标记；具体档位只命中该档', () => {
    expect(matchesFilter(null, 'UNSET')).toBe(true)
    expect(matchesFilter('STRONG', 'UNSET')).toBe(false)
    expect(matchesFilter('STRONG', 'STRONG')).toBe(true)
    expect(matchesFilter(null, 'STRONG')).toBe(false)
    expect(matchesFilter('BACKUP', 'STRONG')).toBe(false)
  })

  it('筛选项顺序：全部 → 四档 → 未标记', () => {
    expect(PREFERENCE_FILTERS.map((f) => f.label)).toEqual([
      '全部', '强烈意向', '可接受', '保底', '不考虑', '未标记',
    ])
  })
})

describe('preference · 计数', () => {
  it('各档真实计数（含 UNSET），ALL = 总数', () => {
    const items = [
      { preference: 'STRONG' },
      { preference: 'STRONG' },
      { preference: 'BACKUP' },
      { preference: null },
      { preference: undefined },
      { preference: 'GARBAGE' }, // 非法 → 视为未标记
    ]
    const c = preferenceCounts(items)
    expect(c.ALL).toBe(6)
    expect(c.STRONG).toBe(2)
    expect(c.BACKUP).toBe(1)
    expect(c.ACCEPTABLE).toBe(0)
    expect(c.EXCLUDED).toBe(0)
    expect(c.UNSET).toBe(3) // null + undefined + 非法
  })
})

describe('preference · 批量对比选择', () => {
  it('canCompare：仅 2–4 个可对比', () => {
    expect(canCompare(0)).toBe(false)
    expect(canCompare(1)).toBe(false)
    expect(canCompare(2)).toBe(true)
    expect(canCompare(4)).toBe(true)
    expect(canCompare(5)).toBe(false)
  })

  it('toggleSelection：切换选中，超上限不新增', () => {
    expect(toggleSelection([], 1)).toEqual([1])
    expect(toggleSelection([1, 2], 1)).toEqual([2]) // 取消
    expect(toggleSelection([1, 2, 3, 4], 5)).toEqual([1, 2, 3, 4]) // 已达上限不动
    expect(toggleSelection([1, 2, 3], 4)).toEqual([1, 2, 3, 4]) // 补到 4
    expect(COMPARE_MAX).toBe(4)
  })
})

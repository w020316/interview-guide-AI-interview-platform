import { describe, it, expect } from 'vitest'
import {
  buildTodoView,
  groupCountText,
  normalizeTodoResponse,
  sortGroups,
  aggregateCount,
  type TodoGroup,
  type TodoTodayResponse,
} from './todo'

/**
 * B 今日待办纯函数单测。
 *
 * 重点锁定 R3「无数据 ≠ 0」：
 *   - 空组不进入展示列表 → DOM 不会出现「0 条」；
 *   - count=null（源不可用）进入 failures，而非被当成 0。
 */

function rawGroup(over: Partial<Record<string, unknown>>): Record<string, unknown> {
  return { key: 'FOLLOW_UP', label: '待跟进投递', count: 0, available: true, items: [], ...over }
}

function item(over: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 1,
    title: '腾讯 · 后端',
    subtitle: '已投递 9 天无更新',
    reason: '投出后 9 天无状态变化（阈值 7 天）',
    route: '/applications',
    routeQuery: { focus: 1 },
    ...over,
  }
}

describe('todo · normalizeTodoResponse', () => {
  it('丢弃缺标题或缺落点的条目，保留合法条目', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({
          items: [item(), { title: '无落点', route: '' }, { route: '/jobs' }, null],
        }),
      ],
    }
    const { groups } = normalizeTodoResponse(raw)
    expect(groups[0].items).toHaveLength(1)
    expect(groups[0].items[0].route).toBe('/applications')
    expect(groups[0].items[0].routeQuery).toEqual({ focus: 1 })
  })

  it('count 以真实 items 数为准；available=false 时 count 恒为 null', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'UPCOMING', count: 99, available: true, items: [item(), item({ id: 2 })] }),
        rawGroup({ key: 'DEADLINE', count: 5, available: false, items: [item()] }),
      ],
    }
    const { groups, failures } = normalizeTodoResponse(raw)
    expect(groups[0].count).toBe(2) // 不采信过期的 99
    expect(groups[1].count).toBeNull() // 源不可用 ≠ 0
    expect(failures.some((f) => f.key === 'DEADLINE')).toBe(true)
  })

  it('available=false 但后端漏给 partialFailures 时自动补齐失败项', () => {
    const raw: TodoTodayResponse = {
      groups: [rawGroup({ key: 'PLANNED', label: '待投递', available: false, items: [] })],
      partialFailures: [],
    }
    const { failures } = normalizeTodoResponse(raw)
    expect(failures).toHaveLength(1)
    expect(failures[0].key).toBe('PLANNED')
    expect(failures[0].message).toContain('待投递')
  })

  it('容错：groups 非数组 / null 响应不抛异常', () => {
    expect(normalizeTodoResponse(null).groups).toEqual([])
    expect(normalizeTodoResponse({ groups: 'oops' } as unknown as TodoTodayResponse).groups).toEqual([])
    expect(normalizeTodoResponse({} as TodoTodayResponse).failures).toEqual([])
  })
})

describe('todo · 排序与计数', () => {
  const g = (key: string, n: number, available = true): TodoGroup => ({
    key,
    label: key,
    count: available ? n : null,
    available,
    items: Array.from({ length: n }, (_, i) => ({
      id: i, title: 't', subtitle: '', reason: '', route: '/applications', routeQuery: null,
    })),
  })

  it('按固定优先级排序（未知组排末尾，稳定）', () => {
    const sorted = sortGroups([g('PLANNED', 1), g('FOLLOW_UP', 1), g('UNKNOWN', 1), g('DEADLINE', 1)])
    expect(sorted.map((x) => x.key)).toEqual(['FOLLOW_UP', 'DEADLINE', 'PLANNED', 'UNKNOWN'])
  })

  it('groupCountText：空组/不可用组返回空串（绝不出现「0 条」）', () => {
    expect(groupCountText(g('FOLLOW_UP', 0))).toBe('')
    expect(groupCountText(g('FOLLOW_UP', 0, false))).toBe('')
    expect(groupCountText(g('FOLLOW_UP', 3))).toBe('3 条')
  })

  it('aggregateCount：只要有一个可用来源就求和；全不可用为 null（不臆造 0）', () => {
    expect(aggregateCount([g('FOLLOW_UP', 2), g('UPCOMING', 0)])).toBe(2)
    expect(aggregateCount([g('FOLLOW_UP', 0, false), g('UPCOMING', 0, false)])).toBeNull()
  })
})

describe('todo · buildTodoView（判断层单一入口）', () => {
  it('全部为空（count=0）→ hasAny=false、groups=[]、total=0，且不产生「0 条」文案', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP', items: [] }),
        rawGroup({ key: 'UPCOMING', items: [] }),
        rawGroup({ key: 'DEADLINE', items: [] }),
        rawGroup({ key: 'PLANNED', items: [] }),
      ],
    }
    const view = buildTodoView(raw)
    expect(view.hasAny).toBe(false)
    expect(view.groups).toEqual([])
    expect(view.total).toBe(0)
    expect(view.groups.every((g) => groupCountText(g) === '')).toBe(true)
  })

  it('部分有内容 → 仅渲染有内容的组，顺序正确', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'PLANNED', items: [item()] }),
        rawGroup({ key: 'FOLLOW_UP', items: [item(), item({ id: 2 })] }),
        rawGroup({ key: 'UPCOMING', items: [] }),
      ],
    }
    const view = buildTodoView(raw)
    expect(view.hasAny).toBe(true)
    expect(view.groups.map((g) => g.key)).toEqual(['FOLLOW_UP', 'PLANNED'])
    expect(view.total).toBe(3)
  })

  it('某组 count=null → 进入 failures，且不出现在展示组里', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP', items: [item()] }),
        rawGroup({ key: 'UPCOMING', available: false, count: null, items: [] }),
      ],
      partialFailures: [{ group: 'UPCOMING', message: '「近期日程」数据暂时取不到' }],
    }
    const view = buildTodoView(raw)
    expect(view.hasAny).toBe(true)
    expect(view.groups.map((g) => g.key)).toEqual(['FOLLOW_UP'])
    expect(view.failures).toHaveLength(1)
    expect(view.failures[0].key).toBe('UPCOMING')
    // anyAvailable 反映「整块是否全部不可用」
    expect(view.anyAvailable).toBe(true)
  })

  it('全部源不可用 → anyAvailable=false、hasAny=false、total=null', () => {
    const raw: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP', available: false, count: null }),
        rawGroup({ key: 'PLANNED', available: false, count: null }),
      ],
      partialFailures: [
        { group: 'FOLLOW_UP', message: 'x' },
        { group: 'PLANNED', message: 'y' },
      ],
    }
    const view = buildTodoView(raw)
    expect(view.anyAvailable).toBe(false)
    expect(view.hasAny).toBe(false)
    expect(view.total).toBeNull()
    expect(view.failures).toHaveLength(2)
  })
})

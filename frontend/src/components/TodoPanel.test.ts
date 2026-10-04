import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'

// 组件内部使用 useRouter；单测只需一个可调用的 push 桩，避免真实路由依赖
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

import TodoPanel from './TodoPanel.vue'
import type { TodoTodayResponse } from '../utils/todo'

/**
 * B 今日待办组件断言级防护（R3「无数据 ≠ 0」）：
 *   - 全空 → DOM **不含**「0 条」，含「今天没有待处理事项」；
 *   - 某组 count=null（源不可用）→ 渲染局部失败态而非 0。
 */

function rawGroup(over: Record<string, unknown>): Record<string, unknown> {
  return { key: 'FOLLOW_UP', label: '待跟进投递', count: 0, available: true, items: [], ...over }
}

function item(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 1,
    title: '腾讯 · 后端',
    subtitle: '已投递 9 天无更新',
    reason: '投递后 9 天无状态变化',
    route: '/applications',
    routeQuery: { focus: 1 },
    ...over,
  }
}

describe('TodoPanel · 空态与计数（R3）', () => {
  it('全空 → 含「今天没有待处理事项」，且 DOM 不含「0 条」', () => {
    const data: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP' }),
        rawGroup({ key: 'UPCOMING', label: '近期日程' }),
        rawGroup({ key: 'DEADLINE', label: '临近截止' }),
        rawGroup({ key: 'PLANNED', label: '待投递' }),
      ],
    }
    const wrapper = mount(TodoPanel, { props: { data } })
    expect(wrapper.text()).toContain('今天没有待处理事项')
    expect(wrapper.text()).not.toContain('0 条')
    expect(wrapper.find('[data-todo-empty]').exists()).toBe(true)
  })

  it('有内容 → 渲染分组与条目，且不出现「0 条」', () => {
    const data: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP', items: [item(), item({ id: 2, title: '字节 · 前端' })] }),
        rawGroup({ key: 'UPCOMING', label: '近期日程', items: [] }),
      ],
    }
    const wrapper = mount(TodoPanel, { props: { data } })
    const text = wrapper.text()
    expect(text).toContain('待跟进投递')
    expect(text).toContain('腾讯 · 后端')
    expect(text).toContain('字节 · 前端')
    expect(text).toContain('2 条')
    expect(text).not.toContain('0 条')
    // 空组（近期日程）不渲染
    expect(text).not.toContain('近期日程')
  })

  it('某组 count=null（源不可用）→ 渲染局部失败态而非 0', () => {
    const data: TodoTodayResponse = {
      groups: [
        rawGroup({ key: 'FOLLOW_UP', items: [item()] }),
        rawGroup({ key: 'UPCOMING', label: '近期日程', available: false, count: null }),
      ],
      partialFailures: [{ group: 'UPCOMING', message: '「近期日程」数据暂时取不到' }],
    }
    const wrapper = mount(TodoPanel, { props: { data } })
    expect(wrapper.find('[data-todo-failures]').exists()).toBe(true)
    expect(wrapper.text()).toContain('数据暂时取不到')
    expect(wrapper.text()).not.toContain('0 条')
    // 仍展示可用的那组
    expect(wrapper.text()).toContain('腾讯 · 后端')
  })

  it('全部数据源不可用 → 整块失败态 + 重试', () => {
    const data: TodoTodayResponse = {
      groups: [rawGroup({ key: 'FOLLOW_UP', available: false, count: null })],
      partialFailures: [{ group: 'FOLLOW_UP', message: '「待跟进投递」数据暂时取不到' }],
    }
    const wrapper = mount(TodoPanel, { props: { data } })
    expect(wrapper.text()).toContain('今日待办暂时取不到')
    expect(wrapper.text()).not.toContain('0 条')
  })

  it('点击刷新 emit retry', async () => {
    const wrapper = mount(TodoPanel, { props: { data: { groups: [] } } })
    await wrapper.find('.todo-refresh').trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })
})

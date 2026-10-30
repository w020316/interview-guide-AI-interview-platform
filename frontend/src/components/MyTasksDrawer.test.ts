import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

/**
 * 我的任务抽屉（M4）的渲染与交互。
 *
 * el-drawer 用桩替代：这里要验证的是「列表内容与操作」，不是浮层的动画/定位。
 */

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const { push } = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('../api', () => ({ default: { get, post } }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import MyTasksDrawer from './MyTasksDrawer.vue'
import { __resetMyTasks, taskDrawerOpen } from '../utils/myTasks'

const DrawerStub = {
  name: 'ElDrawer',
  props: ['modelValue'],
  template: '<div><slot /></div>',
}

/** 已挂载的实例：抽屉打开态是模块级 ref，旧实例不卸载会继续响应 watch 并重复请求 */
const mounted: VueWrapper[] = []

function mountDrawer(): VueWrapper {
  const w = mount(MyTasksDrawer, { global: { stubs: { 'el-drawer': DrawerStub } } })
  mounted.push(w)
  return w
}

const ROWS = [
  { id: 1, type: 'interview.questions', status: 'RUNNING', attempt: 1, maxAttempt: 2, createdAt: '2026-10-30T23:00:00' },
  { id: 2, type: 'interview.evaluate', status: 'FAILED', attempt: 2, maxAttempt: 2, errorMessage: 'AI 服务暂时不可用', createdAt: '2026-10-30T22:58:00' },
  { id: 3, type: 'interview.questions', status: 'SUCCEEDED', attempt: 1, maxAttempt: 2, createdAt: '2026-10-30T22:50:00' },
]

beforeEach(() => {
  __resetMyTasks()
  get.mockReset()
  post.mockReset()
  push.mockReset()
})

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
})

describe('MyTasksDrawer', () => {
  it('打开后拉取并渲染：类型 + 状态 + 第几次，成功行不给取消按钮', async () => {
    taskDrawerOpen.value = true
    get.mockResolvedValue({ items: ROWS, total: 3 })

    const w = mountDrawer()
    await flushPromises()

    const rows = w.findAll('.tk-row')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('AI 出题')
    expect(rows[0].text()).toContain('执行中')
    expect(rows[0].text()).toContain('第 1/2 次')
    // 已完成的任务没有「取消任务」
    expect(rows[2].findAll('button').some((b) => b.text() === '取消任务')).toBe(false)
  })

  it('失败行展示后端文案，并提供「去重新发起」', async () => {
    taskDrawerOpen.value = true
    get.mockResolvedValue({ items: ROWS, total: 3 })

    const w = mountDrawer()
    await flushPromises()

    const failed = w.findAll('.tk-row')[1]
    expect(failed.text()).toContain('AI 服务暂时不可用')
    expect(failed.findAll('button').some((b) => b.text() === '去重新发起')).toBe(true)
  })

  it('筛选切到「失败」只保留失败行', async () => {
    taskDrawerOpen.value = true
    get.mockResolvedValue({ items: ROWS, total: 3 })

    const w = mountDrawer()
    await flushPromises()
    const failedTab = w.findAll('.tk-filter').find((b) => b.text() === '失败')
    await failedTab!.trigger('click')

    expect(w.findAll('.tk-row')).toHaveLength(1)
  })

  it('加载失败（如开关关闭）→ 展示后端文案而不是空列表', async () => {
    taskDrawerOpen.value = true
    get.mockRejectedValue(new Error('任务中心未启用'))

    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.tk-error').text()).toContain('任务中心未启用')
    expect(w.findAll('.tk-row')).toHaveLength(0)
  })

  it('没有任务 → 空态提示（说明任务从哪来）', async () => {
    taskDrawerOpen.value = true
    get.mockResolvedValue({ items: [], total: 0 })

    const w = mountDrawer()
    await flushPromises()

    expect(w.find('.tk-empty').text()).toContain('暂时没有任务')
  })

  it('点「取消任务」→ 调取消接口并刷新列表', async () => {
    taskDrawerOpen.value = true
    get.mockResolvedValue({ items: ROWS, total: 3 })
    post.mockResolvedValue({ status: 'CANCELLED' })

    const w = mountDrawer()
    await flushPromises()
    const cancelBtn = w.findAll('.tk-row')[0].findAll('button').find((b) => b.text() === '取消任务')
    await cancelBtn!.trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/api/tasks/1/cancel')
    expect(get).toHaveBeenCalledTimes(2)
  })
})

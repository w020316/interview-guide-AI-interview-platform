import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 导航角标（M4）。
 *
 * 关键语义：只显示「排队 + 执行中」；开关关闭时永久停止轮询；
 * 页面隐藏时不发请求；重复启动不会挂出多个定时器。
 */

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../api', () => ({ default: { get } }))

import {
  __resetTaskBadge,
  refreshTaskBadge,
  startTaskBadgeWatcher,
  taskBadgeAvailable,
  taskBadgeCount,
} from './taskBadge'

/** jsdom 的 visibilityState 只读，测试里用 defineProperty 覆盖 */
function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => (hidden ? 'hidden' : 'visible'),
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  __resetTaskBadge()
  get.mockReset()
  vi.useFakeTimers()
  setHidden(false)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('refreshTaskBadge —— 只统计未完成任务', () => {
  it('queued + running 计入角标，failed 不计入', async () => {
    get.mockResolvedValueOnce({ queued: 2, running: 1, failed: 5 })

    expect(await refreshTaskBadge()).toBe(true)
    expect(taskBadgeAvailable.value).toBe(true)
    expect(taskBadgeCount.value).toBe(3)
  })

  it('非法/缺失字段按 0 处理（后端字段异常不应把角标算成 NaN）', async () => {
    get.mockResolvedValueOnce({ queued: '3', running: undefined, failed: -1 })

    await refreshTaskBadge()

    expect(taskBadgeCount.value).toBe(0)
  })

  it('开关关闭（503 文案）→ 清空并返回 false（调用方应停止轮询）', async () => {
    get.mockRejectedValueOnce(new Error('任务中心未启用'))

    expect(await refreshTaskBadge()).toBe(false)
    expect(taskBadgeAvailable.value).toBe(false)
    expect(taskBadgeCount.value).toBe(0)
  })

  it('网络抖动 → 保留上一次数字并继续轮询（角标不闪成 0）', async () => {
    get.mockResolvedValueOnce({ queued: 1, running: 1, failed: 0 })
    await refreshTaskBadge()
    get.mockRejectedValueOnce(new Error('Network Error'))

    expect(await refreshTaskBadge()).toBe(true)
    expect(taskBadgeCount.value).toBe(2)
    expect(taskBadgeAvailable.value).toBe(true)
  })
})

describe('startTaskBadgeWatcher —— 节律与生命周期', () => {
  it('启动即刷新一次，之后按间隔轮询', async () => {
    get.mockResolvedValue({ queued: 1, running: 0, failed: 0 })

    const stop = startTaskBadgeWatcher(1_000)
    await vi.advanceTimersByTimeAsync(0)
    expect(get).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(get).toHaveBeenCalledTimes(2)

    stop()
  })

  it('页面隐藏时跳过，回到可见立刻刷新', async () => {
    get.mockResolvedValue({ queued: 1, running: 0, failed: 0 })
    const stop = startTaskBadgeWatcher(1_000)
    await vi.advanceTimersByTimeAsync(0)

    setHidden(true)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(get).toHaveBeenCalledTimes(1)

    setHidden(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(get).toHaveBeenCalledTimes(2)

    stop()
  })

  it('开关关闭 → 自动停止，不再请求', async () => {
    get.mockRejectedValue(new Error('任务中心未启用'))

    startTaskBadgeWatcher(1_000)
    await vi.advanceTimersByTimeAsync(0)
    expect(get).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(5_000)
    expect(get).toHaveBeenCalledTimes(1)
  })

  it('重复启动复用同一个 watcher（避免多个定时器同时打接口）', async () => {
    get.mockResolvedValue({ queued: 0, running: 1, failed: 0 })

    const a = startTaskBadgeWatcher(1_000)
    const b = startTaskBadgeWatcher(1_000)
    expect(b).toBe(a)

    await vi.advanceTimersByTimeAsync(1_000)
    // 只应有一次"启动刷新" + 一次"定时刷新"
    expect(get).toHaveBeenCalledTimes(2)
  })

  it('stop 之后不再请求', async () => {
    get.mockResolvedValue({ queued: 0, running: 0, failed: 0 })
    const stop = startTaskBadgeWatcher(1_000)
    await vi.advanceTimersByTimeAsync(0)

    stop()
    await vi.advanceTimersByTimeAsync(3_000)
    expect(get).toHaveBeenCalledTimes(1)
  })
})

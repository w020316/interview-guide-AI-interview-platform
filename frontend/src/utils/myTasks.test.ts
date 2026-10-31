import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 「我的任务」列表（M4）。
 *
 * 重点是把「状态 → 文案/基调/可操作」这层映射锁住：它决定用户看到的是
 * 「执行中」还是「失败」，以及能不能点取消。
 */

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../api', () => ({ default: { get, post } }))

import {
  __resetMyTasks,
  canCancel,
  canRetry,
  cancelMyTask,
  filterTasks,
  formatTaskTime,
  loadMyTasks,
  canRegenerate,
  taskRoute,
  taskStatusLabel,
  taskStatusTone,
  taskTypeLabel,
  type MyTask,
} from './myTasks'

const T = (over: Partial<MyTask> = {}): MyTask => ({
  id: 1, type: 'interview.questions', status: 'QUEUED', attempt: 0, maxAttempt: 2, ...over,
})

beforeEach(() => {
  __resetMyTasks()
  get.mockReset()
  post.mockReset()
})

describe('状态映射', () => {
  it('类型 → 友好名称；未知类型原样返回（排障时能直接看出是什么）', () => {
    expect(taskTypeLabel('interview.questions')).toBe('AI 出题')
    expect(taskTypeLabel('interview.evaluate')).toBe('作答评分')
    expect(taskTypeLabel('resume.analyze')).toBe('resume.analyze')
    expect(taskTypeLabel(undefined)).toBe('未知任务')
  })

  it('状态 → 中文文案；未知状态原样返回', () => {
    expect(taskStatusLabel('QUEUED')).toBe('排队中')
    expect(taskStatusLabel('RUNNING')).toBe('执行中')
    expect(taskStatusLabel('SUCCEEDED')).toBe('已完成')
    expect(taskStatusLabel('FAILED')).toBe('失败')
    expect(taskStatusLabel('CANCELLED')).toBe('已取消')
    expect(taskStatusLabel('WEIRD')).toBe('WEIRD')
    expect(taskStatusLabel(undefined)).toBe('未知')
  })

  it('状态 → 视觉基调', () => {
    expect(taskStatusTone('QUEUED')).toBe('pending')
    expect(taskStatusTone('RUNNING')).toBe('pending')
    expect(taskStatusTone('SUCCEEDED')).toBe('ok')
    expect(taskStatusTone('FAILED')).toBe('fail')
    expect(taskStatusTone('CANCELLED')).toBe('muted')
  })

  it('只有未结束的任务可取消；失败/取消可重新发起', () => {
    expect(canCancel('QUEUED')).toBe(true)
    expect(canCancel('RUNNING')).toBe(true)
    expect(canCancel('SUCCEEDED')).toBe(false)
    expect(canCancel('FAILED')).toBe(false)
    expect(canRetry('FAILED')).toBe(true)
    expect(canRetry('CANCELLED')).toBe(true)
    expect(canRetry('SUCCEEDED')).toBe(false)
  })

  it('任务类型 → 业务页面', () => {
    expect(taskRoute('interview.questions')).toBe('/interview')
    expect(taskRoute('interview.evaluate')).toBe('/interview')
    expect(taskRoute('unknown.type')).toBeUndefined()
    expect(taskRoute(undefined)).toBeUndefined()
  })

  // P1-2：不带 taskId 的跳转会让用户落在空表单上（结果只存在 ai_task.result_text）
  it('出题任务带上 taskId，评分任务不带（评分上下文刷新后已丢失，不做假装恢复）', () => {
    expect(taskRoute('interview.questions', 42)).toBe('/interview?taskId=42')
    expect(taskRoute('interview.evaluate', 42)).toBe('/interview')
    expect(taskRoute('interview.questions', 0)).toBe('/interview')
  })

  // P1-3：没有这个标记，同简历同岗位最长 7 天只能拿回同一批题
  it('重新生成：出题任务带 regenerate=1，且只有出题支持重新生成', () => {
    expect(taskRoute('interview.questions', 42, true)).toBe('/interview?taskId=42&regenerate=1')
    expect(taskRoute('interview.questions', 42, false)).toBe('/interview?taskId=42')
    expect(taskRoute('interview.evaluate', 42, true)).toBe('/interview')
    expect(canRegenerate('interview.questions')).toBe(true)
    expect(canRegenerate('interview.evaluate')).toBe(false)
    expect(canRegenerate(undefined)).toBe(false)
  })

  it('时间展示：T 换空格并截到分钟；缺失显示破折号', () => {
    expect(formatTaskTime('2026-10-30T23:02:47')).toBe('10-30 23:02')
    expect(formatTaskTime(undefined)).toBe('—')
  })
})

describe('筛选', () => {
  const items = [
    T({ id: 1, status: 'QUEUED' }),
    T({ id: 2, status: 'RUNNING' }),
    T({ id: 3, status: 'FAILED' }),
    T({ id: 4, status: 'SUCCEEDED' }),
    T({ id: 5, status: 'CANCELLED' }),
  ]

  it('进行中 = 排队 + 执行（失败与已完成不混进来）', () => {
    expect(filterTasks(items, 'active').map((t) => t.id)).toEqual([1, 2])
  })

  it('失败 / 已完成 / 全部', () => {
    expect(filterTasks(items, 'failed').map((t) => t.id)).toEqual([3])
    expect(filterTasks(items, 'done').map((t) => t.id)).toEqual([4])
    expect(filterTasks(items, 'all')).toHaveLength(5)
  })
})

describe('接口调用', () => {
  it('列表：取最近 20 条，items 缺失时按空数组（不让页面因字段异常崩掉）', async () => {
    get.mockResolvedValueOnce({ items: [T()], total: 1 })
    const page = await loadMyTasks()
    expect(page.items).toHaveLength(1)
    expect(page.total).toBe(1)
    expect(get).toHaveBeenCalledWith('/api/tasks', { params: { page: 0, size: 20 } })

    get.mockResolvedValueOnce(null)
    const empty = await loadMyTasks()
    expect(empty.items).toEqual([])
  })

  it('列表：503 等错误原样抛出（由抽屉展示，不能让用户误以为「没有任务」）', async () => {
    get.mockRejectedValueOnce(new Error('任务中心未启用'))
    await expect(loadMyTasks()).rejects.toThrow('任务中心未启用')
  })

  it('取消：成功返回 true，失败返回 false（由调用方提示）', async () => {
    post.mockResolvedValueOnce({ status: 'CANCELLED' })
    expect(await cancelMyTask(7)).toBe(true)

    post.mockRejectedValueOnce(new Error('任务状态不允许该操作'))
    expect(await cancelMyTask(7)).toBe(false)
  })
})

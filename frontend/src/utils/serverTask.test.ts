import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 服务端异步任务执行器（M3）。
 *
 * 关键不是「能跑通」，而是两条回退语义：
 *  - 创建阶段任何失败都回退同步（开关关闭时前端必须回到改造前的行为）；
 *  - 轮询阶段不回退（任务已经在跑，重发同步请求会造成双份 AI 计费）。
 */

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('../api', () => ({ default: { get, post } }))

import { __resetAiTasks, doneTasks, runningTasks } from './aiTasks'
import { cancelServerTask, DEFAULT_TASK_TIMEOUT_MS, runServerTask, type ServerTaskOptions } from './serverTask'

const noSleep = async (): Promise<void> => {}

function opts(overrides: Partial<ServerTaskOptions> = {}): ServerTaskOptions {
  return {
    createPath: '/api/tasks/interview/questions',
    body: { resumeText: 'r' },
    label: 'AI 出题',
    route: '/interview',
    fallback: async () => 'SYNC_RESULT',
    sleep: noSleep,
    ...overrides,
  }
}

/** 让时钟按调用次数推进（每调用一次 +step），避免测试里真的等待 */
function tickingNow(step: number): () => number {
  let t = 0
  return () => {
    const v = t
    t += step
    return v
  }
}

beforeEach(() => {
  __resetAiTasks()
  get.mockReset()
  post.mockReset()
})

describe('runServerTask —— 异步优先、同步保底', () => {
  it('创建成功 → 轮询到 SUCCEEDED → 取回结果正文，并在任务中心标记完成', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get
      .mockResolvedValueOnce({ status: 'RUNNING' })
      .mockResolvedValueOnce({ status: 'SUCCEEDED' })
      .mockResolvedValueOnce({ resultText: '[{"question":"q"}]' })

    const text = await runServerTask(opts())

    expect(text).toBe('[{"question":"q"}]')
    expect(post).toHaveBeenCalledWith('/api/tasks/interview/questions', { resumeText: 'r' })
    expect(get).toHaveBeenCalledWith('/api/tasks/7')
    expect(get).toHaveBeenCalledWith('/api/tasks/7/result')
    expect(runningTasks.value).toHaveLength(0)
    expect(doneTasks.value).toHaveLength(1)
    expect(doneTasks.value[0]).toMatchObject({ label: 'AI 出题', ok: true, route: '/interview' })
  })

  it('创建被拒（开关关闭 503 / 旧后端 404） → 回退同步，且不在任务中心留痕', async () => {
    post.mockRejectedValueOnce(new Error('任务中心未启用'))
    const fallback = vi.fn(async () => 'SYNC_RESULT')

    const text = await runServerTask(opts({ fallback }))

    expect(text).toBe('SYNC_RESULT')
    expect(fallback).toHaveBeenCalledTimes(1)
    expect(runningTasks.value).toHaveLength(0)
    expect(doneTasks.value).toHaveLength(0)
  })

  it('创建返回体不含 taskId → 同样回退同步（不把异常当异步任务）', async () => {
    post.mockResolvedValueOnce(undefined)
    const fallback = vi.fn(async () => 'SYNC_RESULT')

    expect(await runServerTask(opts({ fallback }))).toBe('SYNC_RESULT')
    expect(fallback).toHaveBeenCalledTimes(1)
  })

  it('任务 FAILED → 抛错并带上后端文案，任务中心标记失败', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get.mockResolvedValueOnce({ status: 'FAILED', errorMessage: 'AI 服务暂时不可用' })

    await expect(runServerTask(opts())).rejects.toThrow('AI 服务暂时不可用')
    expect(doneTasks.value[0]).toMatchObject({ ok: false, error: 'AI 服务暂时不可用' })
  })

  it('任务被取消 → 明确提示「任务已取消」（不重发同步请求，避免双份计费）', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get.mockResolvedValueOnce({ status: 'CANCELLED' })

    await expect(runServerTask(opts())).rejects.toThrow('任务已取消')
    expect(doneTasks.value[0].ok).toBe(false)
  })

  it('超过最长等待 → 超时报错，而不是无限轮询', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get.mockResolvedValue({ status: 'RUNNING' })

    await expect(
      runServerTask(opts({ timeoutMs: 3_000, now: tickingNow(1_000) })),
    ).rejects.toThrow('任务等待超时')
  })

  it('轮询连续失败达到上限 → 报错；偶发失败会自愈', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ status: 'SUCCEEDED' })
      .mockResolvedValueOnce({ resultText: '[]' })

    expect(await runServerTask(opts())).toBe('[]')
  })

  it('连续失败超过上限 → 停止空转并报错', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get.mockRejectedValue(new Error('network'))

    await expect(runServerTask(opts({ timeoutMs: DEFAULT_TASK_TIMEOUT_MS })))
      .rejects.toThrow('任务状态查询失败')
    expect(get.mock.calls.length).toBeLessThanOrEqual(6)
  })

  it('成功但结果正文缺失 → 判失败（不能返回空串让调用方「解析成功但没内容」）', async () => {
    post.mockResolvedValueOnce({ taskId: 7, status: 'QUEUED' })
    get
      .mockResolvedValueOnce({ status: 'SUCCEEDED' })
      .mockResolvedValueOnce({ resultText: '   ' })

    await expect(runServerTask(opts())).rejects.toThrow('任务已完成但没有返回结果')
  })
})

describe('cancelServerTask —— 尽力而为', () => {
  it('取消成功 → 走取消接口', async () => {
    post.mockResolvedValueOnce({ status: 'CANCELLED' })
    await cancelServerTask(7)
    expect(post).toHaveBeenCalledWith('/api/tasks/7/cancel')
  })

  it('取消失败（任务已结束 / 网络问题）→ 静默，不影响主流程', async () => {
    post.mockRejectedValueOnce(new Error('conflict'))
    await expect(cancelServerTask(7)).resolves.toBeUndefined()
  })
})

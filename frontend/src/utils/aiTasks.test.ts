import { beforeEach, describe, expect, it } from 'vitest'
import {
  __resetAiTasks,
  DONE_TTL_MS,
  dismissAiTask,
  doneTasks,
  elapsedSeconds,
  finishAiTask,
  pruneDoneTasks,
  runningTasks,
  startAiTask,
  taskMetaForUrl,
} from './aiTasks'

describe('aiTasks —— 全局 AI 任务注册表（N3）', () => {
  beforeEach(() => __resetAiTasks())

  it('taskMetaForUrl：长耗时 AI 任务映射到友好标签与结果页', () => {
    expect(taskMetaForUrl('/api/resume/analyze')).toEqual({ label: '简历分析', route: '/resume' })
    expect(taskMetaForUrl('/api/interview/questions')).toEqual({ label: 'AI 出题', route: '/interview' })
    expect(taskMetaForUrl('/api/job/gap')).toEqual({ label: '差距分析', route: '/job' })
  })

  it('taskMetaForUrl：智能体对话 / RAG 问答 / 普通接口不登记（避免通知刷屏）', () => {
    expect(taskMetaForUrl('/api/agent/chat')).toBeNull()
    expect(taskMetaForUrl('/api/knowledge/ask')).toBeNull()
    expect(taskMetaForUrl('/api/jobs?page=0')).toBeNull()
    expect(taskMetaForUrl(undefined)).toBeNull()
  })

  it('start → finish(成功)：从进行中移入完成通知', () => {
    const id = startAiTask('/api/resume/analyze')
    expect(id).not.toBeNull()
    expect(runningTasks.value).toHaveLength(1)
    expect(doneTasks.value).toHaveLength(0)

    finishAiTask(id, true)
    expect(runningTasks.value).toHaveLength(0)
    expect(doneTasks.value).toHaveLength(1)
    expect(doneTasks.value[0].ok).toBe(true)
    expect(doneTasks.value[0].label).toBe('简历分析')
  })

  it('非长耗时任务不登记：startAiTask 返回 null 且不产生任何状态', () => {
    expect(startAiTask('/api/jobs')).toBeNull()
    expect(runningTasks.value).toHaveLength(0)
    expect(doneTasks.value).toHaveLength(0)
  })

  it('finish：未知/空 id 幂等忽略；失败态记录原因；重复收口不产生第二条', () => {
    finishAiTask(null, true)
    finishAiTask('ai-999', true)
    expect(doneTasks.value).toHaveLength(0)

    const id = startAiTask('/api/interview/evaluate')
    finishAiTask(id, false, '服务未就绪')
    expect(doneTasks.value[0].ok).toBe(false)
    expect(doneTasks.value[0].error).toBe('服务未就绪')
    finishAiTask(id, true)
    expect(doneTasks.value).toHaveLength(1)
  })

  it('并发多任务：各自独立收口', () => {
    const a = startAiTask('/api/resume/analyze')
    const b = startAiTask('/api/interview/questions')
    expect(runningTasks.value).toHaveLength(2)

    finishAiTask(a, true)
    expect(runningTasks.value.map((t) => t.id)).toEqual([b])

    finishAiTask(b, true)
    expect(runningTasks.value).toHaveLength(0)
    expect(doneTasks.value).toHaveLength(2)
  })

  it('dismissAiTask：只关掉指定通知', () => {
    const a = startAiTask('/api/resume/analyze')
    const b = startAiTask('/api/job/analyze')
    finishAiTask(a, true)
    finishAiTask(b, true)

    dismissAiTask(a as string)
    expect(doneTasks.value.map((t) => t.id)).toEqual([b])
  })

  it('pruneDoneTasks：超过 TTL 的完成通知自动清掉', () => {
    const id = startAiTask('/api/resume/analyze')
    finishAiTask(id, true)
    const finishedAt = doneTasks.value[0].finishedAt as number

    pruneDoneTasks(finishedAt + DONE_TTL_MS - 1)
    expect(doneTasks.value).toHaveLength(1)

    pruneDoneTasks(finishedAt + DONE_TTL_MS)
    expect(doneTasks.value).toHaveLength(0)
  })

  it('elapsedSeconds：向下取整且不为负', () => {
    expect(elapsedSeconds(1000, 1000)).toBe(0)
    expect(elapsedSeconds(1000, 3999)).toBe(2)
    expect(elapsedSeconds(1000, 999)).toBe(0)
  })
})

import api from '../api'
import { finishAiTask, startServerAiTask } from './aiTasks'

/**
 * 服务端异步 AI 任务执行器（阶段六 M3）。
 *
 * ── 解决什么问题 ────────────────────────────────────────────────────
 * 出题实测 77s、评分 11–29s：此前用户必须停在当前页盯着进度条，切走就彻底失去反馈。
 * 后端任务中心（`/api/tasks`）把长耗时调用变成可查询的后台任务后，这里负责
 * 「创建 → 轮询 → 取结果 → 收口任务中心通知」的全流程。
 *
 * ── 关键取舍：异步是优化，同步是保底 ─────────────────────────────────
 * 任务中心开关默认关闭，关闭时创建接口返回 503。**创建阶段任何失败都直接回退同步链路**，
 * 而不是把错误抛给用户：
 *   - 开关关闭 / 后端旧版本没有该接口 → 404/503；
 *   - 网络抖动、限流（429）→ 同步链路会给出同样的真实错误，错误文案不丢失。
 * 轮询阶段则不再回退（任务已经在跑，重跑同步请求会造成双份 AI 计费），
 * 连续多次失败或超时才判失败。
 */

/** 默认最长等待：出题 77s + 队列等待，留一倍余量 */
export const DEFAULT_TASK_TIMEOUT_MS = 240_000
/** 默认轮询间隔：2s，对后端与免费层额度都很轻 */
export const DEFAULT_POLL_MS = 2_000
/** 轮询连续失败上限：超过则认为网络已断，不再空转 */
const MAX_POLL_FAILURES = 5

export interface ServerTaskOptions {
  /** 创建任务的接口，如 `/api/tasks/interview/questions` */
  createPath: string
  /** 创建任务的请求体 */
  body: unknown
  /** 任务中心里的友好文案，如「AI 出题」 */
  label: string
  /** 完成后「去看看」的目标路由 */
  route?: string
  /** 异步不可用时的同步兜底调用（必须返回结果原文） */
  fallback: () => Promise<string>
  timeoutMs?: number
  pollMs?: number
  /** 测试注入：避免真实等待 */
  sleep?: (ms: number) => Promise<void>
  /** 测试注入：时间源 */
  now?: () => number
}

/** 服务端任务状态（只取前端关心的字段） */
interface TaskView {
  status?: string
  errorMessage?: string
  progress?: number
}

const SUCCEEDED = 'SUCCEEDED'
const FAILED = 'FAILED'
const CANCELLED = 'CANCELLED'

/** 让出事件循环（真实等待；测试可注入空实现） */
function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 执行一次「优先异步、失败回退同步」的 AI 调用，返回结果原文。
 *
 * @returns 结果文本（题目 JSON / 评分 JSON 原文，与同步接口返回值一致）
 */
export async function runServerTask(opts: ServerTaskOptions): Promise<string> {
  const taskId = await tryCreateTask(opts.createPath, opts.body)
  if (taskId == null) {
    // 异步不可用（开关关闭 / 旧后端 / 网络问题）：走同步链路，行为与改造前完全一致
    return opts.fallback()
  }

  const clientId = startServerAiTask(opts.label, opts.route, taskId)
  try {
    const text = await pollUntilDone(taskId, opts)
    finishAiTask(clientId, true)
    return text
  } catch (e) {
    finishAiTask(clientId, false, (e as Error)?.message || '任务失败')
    throw e
  }
}

/** 创建任务；任何异常或返回体不含数字 taskId 都视为「异步不可用」 */
async function tryCreateTask(path: string, body: unknown): Promise<number | null> {
  try {
    const res = (await api.post(path, body)) as unknown as { taskId?: number } | null | undefined
    const id = res?.taskId
    return typeof id === 'number' && id > 0 ? id : null
  } catch {
    return null
  }
}

/** 轮询到终态并取回结果正文 */
async function pollUntilDone(taskId: number, opts: ServerTaskOptions): Promise<string> {
  const sleep = opts.sleep ?? defaultSleep
  const now = opts.now ?? Date.now
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TASK_TIMEOUT_MS
  const pollMs = opts.pollMs ?? DEFAULT_POLL_MS
  const deadline = now() + timeoutMs
  let failures = 0

  while (now() < deadline) {
    await sleep(pollMs)
    let task: TaskView | null | undefined
    try {
      task = (await api.get(`/api/tasks/${taskId}`)) as unknown as TaskView | null
      failures = 0
    } catch (e) {
      failures += 1
      if (failures >= MAX_POLL_FAILURES) {
        throw new Error('任务状态查询失败，请稍后在任务中心重试')
      }
      continue
    }
    const status = task?.status
    if (status === SUCCEEDED) {
      return fetchResult(taskId)
    }
    if (status === FAILED) {
      throw new Error(task?.errorMessage || '任务执行失败，请重试')
    }
    if (status === CANCELLED) {
      throw new Error('任务已取消')
    }
  }
  throw new Error('任务等待超时，请稍后在任务中心查看结果')
}

/** 取结果正文；缺失时明确失败，而不是返回空串让调用方「解析成功但内容为空」 */
async function fetchResult(taskId: number): Promise<string> {
  const data = (await api.get(`/api/tasks/${taskId}/result`)) as unknown as { resultText?: string } | null
  const text = data?.resultText
  if (typeof text !== 'string' || text.trim() === '') {
    throw new Error('任务已完成但没有返回结果，请重试')
  }
  return text
}

/** 取消服务端任务（任务中心「取消」按钮用）；失败静默——取消只是尽力而为 */
export async function cancelServerTask(taskId: number): Promise<void> {
  try {
    await api.post(`/api/tasks/${taskId}/cancel`)
  } catch {
    /* 任务可能已结束或已过期，取消失败不影响主流程 */
  }
}


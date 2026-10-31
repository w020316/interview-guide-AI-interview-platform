import { ref } from 'vue'
import api from '../api'

/**
 * 「我的任务」列表（阶段六 M4 补完 M2 的列表 UI）。
 *
 * 分工：`utils/serverTask.ts` 负责「跑一个任务」，这里只负责「看历史」。
 * 列表接口已经存在（M2），本文件只做三件事：取数、纯函数式的状态文案/配色映射、
 * 抽屉开关这类跨组件状态（项目没有引入 Pinia，模块级 ref 即单例）。
 */

export interface MyTask {
  id: number
  type: string
  status: string
  progress?: number
  attempt?: number
  maxAttempt?: number
  errorCode?: string
  errorMessage?: string
  createdAt?: string
  finishedAt?: string
}

export interface TaskPage {
  items: MyTask[]
  total: number
  page: number
  size: number
}

/** 任务类型 → 友好名称（未知类型原样展示，便于排障时一眼看出是什么） */
const TYPE_LABELS: Record<string, string> = {
  'interview.questions': 'AI 出题',
  'interview.evaluate': '作答评分',
}

/** 状态 → 中文文案 */
const STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

/** 状态 → 视觉基调（配色交给 CSS 令牌，不在这里写死颜色） */
export type TaskTone = 'pending' | 'ok' | 'fail' | 'muted'

export function taskTypeLabel(type?: string): string {
  if (!type) return '未知任务'
  return TYPE_LABELS[type] || type
}

export function taskStatusLabel(status?: string): string {
  if (!status) return '未知'
  return STATUS_LABELS[status] || status
}

export function taskStatusTone(status?: string): TaskTone {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
      return 'pending'
    case 'SUCCEEDED':
      return 'ok'
    case 'FAILED':
      return 'fail'
    default:
      return 'muted'
  }
}

/** 只有未结束的任务可以取消（与后端状态机一致） */
export function canCancel(status?: string): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

/** 失败 / 取消的任务可以重新发起（后端允许同幂等键在非 ACTIVE 状态下新建） */
export function canRetry(status?: string): boolean {
  return status === 'FAILED' || status === 'CANCELLED'
}

/**
 * 任务对应的业务页面（点「去看看」跳这里）。
 *
 * <p>P1-2（2026-10-31）：出题任务必须带上 {@code taskId} —— 结果正文只存在
 * {@code ai_task.result_text}，面试页只有拿到 id 才能取回并重建会话。
 * 不带的后果是「回来落在空表单」，用户只能重新生成、再烧一次 AI 配额。
 *
 * <p>P1-3：{@code regenerate} 为真时额外带 {@code &regenerate=1}，面试页据此
 * 用原任务的入参快照重新发一次出题（后端会在幂等键里掺随机量）。
 *
 * <p>评分任务不带 id：评分结果依附「某道题 + 用户答案」的页内上下文，
 * 刷新后上下文已丢失，回填方式需产品决策（见 08-下一步交接.md），此处不做假装恢复。
 */
export function taskRoute(type?: string, taskId?: number, regenerate = false): string | undefined {
  if (!type) return undefined
  if (type === 'interview.questions' && taskId) {
    return regenerate ? `/interview?taskId=${taskId}&regenerate=1` : `/interview?taskId=${taskId}`
  }
  if (type.startsWith('interview.')) return '/interview'
  return undefined
}

/** 是否支持「重新生成」：目前只有出题有意义（评分重跑同一份答案只会得到同一个分数） */
export function canRegenerate(type?: string): boolean {
  return type === 'interview.questions'
}

/** 抽屉是否展开（模块级单例，导航角标、头像菜单都改这个） */
export const taskDrawerOpen = ref(false)

export function openTaskDrawer(): void {
  taskDrawerOpen.value = true
}

export function closeTaskDrawer(): void {
  taskDrawerOpen.value = false
}

/** 仅供单测重置（模块级单例跨用例残留会让下一个用例一挂载就请求） */
export function __resetMyTasks(): void {
  taskDrawerOpen.value = false
}

/** 列表筛选：全部 / 进行中（排队+执行）/ 失败 / 已完成 */
export type TaskFilter = 'all' | 'active' | 'failed' | 'done'

export function filterTasks(items: MyTask[], filter: TaskFilter): MyTask[] {
  switch (filter) {
    case 'active':
      return items.filter((t) => t.status === 'QUEUED' || t.status === 'RUNNING')
    case 'failed':
      return items.filter((t) => t.status === 'FAILED')
    case 'done':
      return items.filter((t) => t.status === 'SUCCEEDED')
    default:
      return items
  }
}

/**
 * 拉取最近任务。
 *
 * @throws Error（后端 503「任务中心未启用」等），由抽屉展示文案 —— 这里不吞异常，
 *         否则用户会以为「我真的没有任务」
 */
export async function loadMyTasks(size = 20): Promise<TaskPage> {
  const data = (await api.get('/api/tasks', { params: { page: 0, size } })) as unknown as Partial<TaskPage> | null
  const items = Array.isArray(data?.items) ? (data?.items as MyTask[]) : []
  return {
    items,
    total: typeof data?.total === 'number' ? data.total : items.length,
    page: 0,
    size,
  }
}

/** 取消任务；返回是否成功（失败已由调用方提示，这里不抛） */
export async function cancelMyTask(id: number): Promise<boolean> {
  try {
    await api.post(`/api/tasks/${id}/cancel`)
    return true
  } catch {
    return false
  }
}

/** 本地时间（后端返回 LocalDateTime 字符串，缺时区信息，按原样展示更诚实） */
export function formatTaskTime(iso?: string): string {
  if (!iso) return '—'
  const t = iso.replace('T', ' ')
  return t.length >= 16 ? t.slice(5, 16) : t
}

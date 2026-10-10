import { computed, ref } from 'vue'

/**
 * 全局 AI 任务注册表（N3 · AI 任务后台化 + 完成通知）
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * AI 调用（出题 / 作答评分 / 简历分析 / 岗位分析…）实测中位 ≈7s、可达 19s 以上，
 * 此前用户只能盯着当前页的骨架屏等：一旦切走页面，就彻底失去「还在跑 / 跑完了」的反馈，
 * 只能凭记忆回来再看看。
 *
 * ── 做法（关键取舍）──────────────────────────────────────────────────
 * **不逐个视图接线**，而是在 api 拦截器层按 URL 识别 AI 请求并托管（见 api/index.ts），
 * 视图零改动。本模块只维护状态，UI 由 components/AiTaskCenter.vue 渲染。
 *
 * - 只登记**页面级的长耗时 AI 任务**（见 TASK_LABELS）；智能体对话 / RAG 问答这类
 *   高频、就地交互的调用不登记，避免通知刷屏。
 * - 模块级 `ref` 即全局单例（项目未引入 Pinia），跨路由常驻。
 */

export interface AiTask {
  id: string
  /** 友好标签，如「简历分析」 */
  label: string
  /** 完成后可一键跳回的页面（无则不展示跳转） */
  route?: string
  startedAt: number
  finishedAt?: number
  /** 是否成功（仅完成态有值） */
  ok?: boolean
  /** 失败时的简短原因（仅失败态有值） */
  error?: string
}

/** 长耗时 AI 任务：路径特征 → 友好标签 + 完成后跳回的页面 */
const TASK_LABELS: ReadonlyArray<{ frag: string; label: string; route?: string }> = [
  { frag: '/api/resume/upload', label: '简历解析', route: '/resume' },
  { frag: '/api/resume/analyze', label: '简历分析', route: '/resume' },
  { frag: '/api/resume/import-url', label: '简历导入', route: '/resume' },
  { frag: '/api/resume/optimize', label: '简历优化', route: '/resume' },
  { frag: '/api/interview/questions', label: 'AI 出题', route: '/interview' },
  { frag: '/api/interview/evaluate', label: '作答评分', route: '/interview' },
  { frag: '/api/interview/followup', label: '生成追问', route: '/interview' },
  { frag: '/api/job/import-url', label: '岗位导入', route: '/job' },
  { frag: '/api/job/analyze', label: '岗位分析', route: '/job' },
  { frag: '/api/job/gap', label: '差距分析', route: '/job' },
  { frag: '/api/job/letter', label: '求职信生成', route: '/job' },
]

/** 完成通知的存活时长：到点自动消失，避免堆积 */
export const DONE_TTL_MS = 60_000

const running = ref<AiTask[]>([])
const done = ref<AiTask[]>([])
let seq = 0

function nowMs(): number {
  return Date.now()
}

/** 按 URL 解析任务标签；非长耗时 AI 任务返回 null */
export function taskMetaForUrl(url?: string): { label: string; route?: string } | null {
  if (!url) return null
  const hit = TASK_LABELS.find((t) => url.includes(t.frag))
  return hit ? { label: hit.label, route: hit.route } : null
}

/**
 * 登记一个进行中的 AI 任务，返回任务 id；非长耗时任务返回 null。
 * 失败/异常一律吞掉——任务统计绝不能影响真实请求。
 */
export function startAiTask(url?: string): string | null {
  try {
    const meta = taskMetaForUrl(url)
    if (!meta) return null
    const id = `ai-${++seq}`
    running.value.push({ id, label: meta.label, route: meta.route, startedAt: nowMs() })
    return id
  } catch {
    return null
  }
}

/** 收口任务：成功 → 完成通知；失败 → 失败通知。幂等，未知 id 直接忽略。 */
export function finishAiTask(id: string | null | undefined, ok: boolean, error?: string): void {
  try {
    if (!id) return
    const i = running.value.findIndex((t) => t.id === id)
    if (i === -1) return
    const [task] = running.value.splice(i, 1)
    task.finishedAt = nowMs()
    task.ok = ok
    if (!ok && error) task.error = error
    done.value.push(task)
  } catch {
    /* 通知失败绝不影响请求链路 */
  }
}

/** 关闭一条完成通知 */
export function dismissAiTask(id: string): void {
  done.value = done.value.filter((t) => t.id !== id)
}

/** 清理超期完成通知（组件定时调用） */
export function pruneDoneTasks(at: number = nowMs()): void {
  done.value = done.value.filter((t) => t.finishedAt == null || at - t.finishedAt < DONE_TTL_MS)
}

/** 进行中的任务（只读） */
export const runningTasks = computed<readonly AiTask[]>(() => running.value)
/** 完成通知（只读） */
export const doneTasks = computed<readonly AiTask[]>(() => done.value)

/** 已运行秒数（向下取整，最小 0） */
export function elapsedSeconds(startedAt: number, at: number = nowMs()): number {
  return Math.max(0, Math.floor((at - startedAt) / 1000))
}

/** 仅供单测重置内部状态 */
export function __resetAiTasks(): void {
  running.value = []
  done.value = []
  seq = 0
}

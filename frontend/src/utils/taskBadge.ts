import { computed, ref } from 'vue'
import api from '../api'

/**
 * 导航角标：未完成 AI 任务数（阶段六 M4）。
 *
 * ── 口径：只显示「排队 + 执行中」，不显示失败数 ─────────────────────
 * 失败任务已经通过任务中心浮层的失败通知告知用户，且失败记录会保留 30 天；
 * 若把它做成常驻角标，红色数字会一直挂着且用户无法自行消除（没有「已读/忽略」入口），
 * 最终变成谁都无视的噪音。所以角标只表达「还有事在跑」。
 *
 * ── 轮询约束 ────────────────────────────────────────────────────────
 * - 页面隐藏时不发请求（`visibilitychange`），回到可见立刻刷新一次；
 * - 30s 一次，与任务中心 §6.4 的口径一致；
 * - 服务端返回「任务中心未启用」（开关关闭）时**永久停止**轮询 ——
 *   否则每个打开的页面会一直打一个必然失败/无意义的接口。
 */

export interface TaskSummaryCounts {
  queued: number
  running: number
  failed: number
}

/** 角标轮询间隔（30s/次） */
export const TASK_BADGE_POLL_MS = 30_000

/** 开关关闭时后端返回的固定文案（见 AiTaskController） */
const DISABLED_MARK = '任务中心未启用'

const counts = ref<TaskSummaryCounts>({ queued: 0, running: 0, failed: 0 })
/** 服务端是否启用任务中心；未确认前为 false（此时角标不显示） */
const available = ref(false)

/** 角标数字：排队 + 执行中 */
export const taskBadgeCount = computed(() => {
  if (!available.value) return 0
  return counts.value.queued + counts.value.running
})

/** 服务端是否确认可用（测试与调试用） */
export const taskBadgeAvailable = computed(() => available.value)

/** 仅供单测重置（同时停掉已挂的定时器，避免用例之间互相污染） */
export function __resetTaskBadge(): void {
  stopWatcher?.()
  counts.value = { queued: 0, running: 0, failed: 0 }
  available.value = false
}

/**
 * 刷新一次角标。
 *
 * @returns 是否仍可继续轮询（false = 任务中心未启用，应停止）
 */
export async function refreshTaskBadge(): Promise<boolean> {
  try {
    const s = (await api.get('/api/tasks/summary')) as unknown as Partial<TaskSummaryCounts> | null
    counts.value = {
      queued: num(s?.queued),
      running: num(s?.running),
      failed: num(s?.failed),
    }
    available.value = true
    return true
  } catch (e) {
    if ((e as Error)?.message?.includes(DISABLED_MARK)) {
      // 开关关闭：清空并永久停止，避免无意义轮询
      counts.value = { queued: 0, running: 0, failed: 0 }
      available.value = false
      return false
    }
    // 网络抖动 / 401 等：保留上一次数字，下一轮再试（不回退成 0 以免角标闪一下消失）
    return true
  }
}

/**
 * 启动角标轮询，返回停止函数（幂等：重复调用复用同一个 watcher）。
 *
 * @param intervalMs 轮询间隔，测试可缩短
 */
export function startTaskBadgeWatcher(intervalMs: number = TASK_BADGE_POLL_MS): () => void {
  if (stopWatcher) return stopWatcher

  let stopped = false
  let timer: number | undefined

  const stop = (): void => {
    if (stopped) return
    stopped = true
    if (timer !== undefined) window.clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
    stopWatcher = null
  }

  const tick = async (): Promise<void> => {
    if (stopped || document.visibilityState === 'hidden') return
    const ok = await refreshTaskBadge()
    if (!ok) stop()
  }

  const onVisible = (): void => {
    if (document.visibilityState === 'visible') void tick()
  }

  document.addEventListener('visibilitychange', onVisible)
  timer = window.setInterval(() => { void tick() }, intervalMs)
  stopWatcher = stop
  void tick()
  return stop
}

/** 当前活跃的停止函数（模块级单例，避免重复挂载多个定时器） */
let stopWatcher: (() => void) | null = null

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0
}

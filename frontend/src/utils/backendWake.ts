import axios from 'axios'
import { reactive } from 'vue'
import { apiBaseUrl } from '../api/baseUrl'

/**
 * 后端冷启动唤醒器（单飞 / single-flight）
 *
 * ── 背景 ─────────────────────────────────────────────────────────────
 * 后端部署在 Render 免费层：512MB 实例、15 分钟无请求即休眠。
 * 实测冷启动耗时 **约 98 秒**（Spring Boot 3.3 + Spring AI 全家桶 + pgvector，
 * 见 2026-09-19 线上复测：/actuator/health 首个请求 98.1s 返回 200）。
 *
 * 此前前端只在**认证请求失败之后**才开始唤醒轮询，且登录接口本地超时仅 90s，
 * 小于冷启动耗时 —— 结果必然是：
 *   用户点「登录」→ 干等 90s → 超时失败 → 才出现「正在冷启动」提示 → 再等一轮才成功。
 * 表现为「登录界面加载停滞、认证过程无响应」。
 *
 * ── 本模块职责 ───────────────────────────────────────────────────────
 * 1. prewarm()：应用启动即发起一次探测，让实例在用户输入账号密码期间就开始启动
 *    （fire-and-forget，不阻塞渲染）。
 * 2. ensureAwake()：单飞等待后端就绪 —— 多个调用方共享同一次探测，避免并发探测风暴。
 * 3. wakeState：响应式状态，供 UI 展示「正在唤醒后端」进度。
 *
 * 探测使用**裸 axios**（不经过 api/index.ts 的拦截器），避免拦截器自递归与错误文案污染。
 */

export type WakeStatus = 'idle' | 'probing' | 'ready' | 'failed'

/**
 * 可覆盖的唤醒参数。
 * 测试通过调小 budgetMs / retryIntervalMs 让失败路径快速返回，避免用例挂起。
 */
export const wakeConfig = {
  /** 单次探测超时：免费层实例刚开始接受连接时可能较慢，给 20s */
  probeTimeoutMs: 20000,
  /** 两次探测之间的间隔 */
  retryIntervalMs: 3000,
  /** 唤醒总预算：冷启动约 98s，留出充足余量（含队列/构建缓存未命中的更慢情形） */
  budgetMs: 150000,
  /** 探测成功后的「已知就绪」有效期：避免每次请求都先探测一次 */
  readyTtlMs: 30000,
}

/** 响应式唤醒状态，供 UI 使用 */
export const wakeState = reactive({
  status: 'idle' as WakeStatus,
  /** 已等待时长（毫秒），用于展示进度 */
  elapsedMs: 0,
  /** 已发出的探测次数 */
  attempts: 0,
})

/** 单飞中的探测 Promise；非 null 表示已有调用在进行 */
let inflight: Promise<boolean> | null = null
/** 最近一次探测成功的时间戳 */
let lastReadyAt = 0

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 后端是否处于「已知就绪」状态（短时间内探测成功过） */
export function isBackendKnownReady(): boolean {
  return wakeState.status === 'ready' && Date.now() - lastReadyAt < wakeConfig.readyTtlMs
}

/**
 * 单次探测：命中无鉴权的轻量端点 /api/info。
 *
 * 设计约束（2026-09-19 真机回归修复，务必保持）：
 * 1. **不得携带任何非 CORS 安全列表请求头**（原实现加了 `Cache-Control: no-cache`）。
 *    跨域下 `Cache-Control` 不属于 safelisted header，会强制浏览器先发 OPTIONS 预检；
 *    而后端 `SecurityConfig.setAllowedHeaders` 白名单并不包含它 → 预检返回 403 且不带
 *    CORS 响应头 → 浏览器直接拦截真实请求。表现为「探测永远失败、唤醒器空转到预算耗尽」，
 *    并使登录流程白等满 150s（比修复前的 90s 超时更糟）。
 *    改缓存策略请用 URL 上的时间戳参数（`_t`），不要动请求头。
 * 2. 用 `validateStatus: () => true` 把「任何 HTTP 响应」都视为实例已唤醒——
 *    服务器返回 4xx/5xx 同样证明进程已起来，无需继续轮询。只有网络层错误
 *    （连接被拒 / 超时 / CORS 拦截）才判定为未就绪。
 */
async function probeOnce(): Promise<boolean> {
  try {
    await axios.get(`${apiBaseUrl}/api/info`, {
      timeout: wakeConfig.probeTimeoutMs,
      params: { _t: Date.now() },
      validateStatus: () => true,
    })
    return true
  } catch {
    return false
  }
}

/**
 * 确保后端已唤醒。多个并发调用共享同一次探测（单飞）。
 *
 * @param budgetMs 唤醒总预算，默认 {@link wakeConfig.budgetMs}
 * @returns 后端是否在预算内就绪
 */
export function ensureAwake(budgetMs: number = wakeConfig.budgetMs): Promise<boolean> {
  if (isBackendKnownReady()) {
    return Promise.resolve(true)
  }
  if (inflight) {
    return inflight
  }
  const start = Date.now()
  wakeState.status = 'probing'
  wakeState.elapsedMs = 0
  wakeState.attempts = 0

  inflight = (async () => {
    let ok = await probeOnce()
    wakeState.attempts = 1
    while (!ok && Date.now() - start < budgetMs) {
      wakeState.elapsedMs = Date.now() - start
      await sleep(wakeConfig.retryIntervalMs)
      ok = await probeOnce()
      wakeState.attempts += 1
    }
    wakeState.elapsedMs = Date.now() - start
    lastReadyAt = ok ? Date.now() : 0
    wakeState.status = ok ? 'ready' : 'failed'
    return ok
  })()

  // 无论成功失败都要释放单飞锁，否则后续调用会永远复用一个已完成的 Promise
  inflight = inflight.finally(() => {
    inflight = null
  })
  return inflight
}

/**
 * 应用启动即预热（fire-and-forget）：不阻塞首屏，失败也不抛错。
 * 用户在登录页输入账号密码的时间通常有数十秒，足以覆盖大部分冷启动耗时。
 */
export function prewarmBackend(): void {
  void ensureAwake().catch(() => undefined)
}

/** 仅供测试重置内部状态 */
export function __resetWakeStateForTest(): void {
  inflight = null
  lastReadyAt = 0
  wakeState.status = 'idle'
  wakeState.elapsedMs = 0
  wakeState.attempts = 0
}

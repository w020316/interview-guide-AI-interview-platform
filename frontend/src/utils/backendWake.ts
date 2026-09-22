import axios from 'axios'
import { reactive } from 'vue'
import { apiBaseUrl } from '../api/baseUrl'

/**
 * 后端冷启动唤醒器（单飞 / single-flight）
 *
 * ── 背景 ─────────────────────────────────────────────────────────────
 * 后端部署在 Render 免费层：512MB 实例、15 分钟无请求即休眠。
 *
 * 2026-09-22 线上复测（真机问题：连续两次登录都在 150s 后失败）：
 *   从休眠态发起请求，**实测 355s（≈5.9 分钟）才拿到首个 200 响应**
 *   （另一次用「T+300 补发请求」定位到约 338s 就绪，两次同量级）。
 *   而此前唤醒总预算只有 150s、登录接口本地超时 150s —— 两者都远小于真实
 *   冷启动耗时，所以「点登录 → 干等 150s → 必定失败」是必然结果，与网络无关。
 *   更糟的是保活工作流自己 480s 的唤醒预算也失败过（2026-09-22T05:22:13Z run），
 *   说明**慢启动可能超过 8 分钟**，预算不能再按"够用就好"来设。
 *   早期记录的「98s 冷启动」已随版本迭代（v1.32 → v1.38，Bean 数与类加载量
 *   显著增加，仍限制在 -Xmx220m）而失效，**不要再用 98s 作为预算依据**。
 *
 * ── 本模块职责 ───────────────────────────────────────────────────────
 * 1. prewarm()：应用启动即发起一次探测，让实例在用户输入账号密码期间就开始启动
 *    （fire-and-forget，不阻塞渲染）。
 * 2. ensureAwake()：单飞等待后端就绪 —— 多个调用方共享同一次探测，避免并发探测风暴；
 *    且**支持在等待过程中被延长预算**（用户点「继续等待」时不会从头再等一轮）。
 * 3. wakeState：响应式状态，供 UI 展示「正在唤醒后端」的实时进度与失败原因分类。
 * 4. installWakeRecovery()：页面重新可见 / 网络恢复时自动补一次探测。
 *
 * 探测使用**裸 axios**（不经过 api/index.ts 的拦截器），避免拦截器自递归与错误文案污染。
 */

export type WakeStatus = 'idle' | 'probing' | 'ready' | 'failed'

/**
 * 探测失败原因分类 —— 决定 UI 文案，避免把「后端还在启动」误报成「请检查网络」。
 * - booting：连接被挂起（本地超时）或边缘节点错误页 → 后端进程仍在启动
 * - network：连接直接被拒/域名解析失败 → 用户侧网络或 CORS 问题
 */
export type WakeErrorKind = 'none' | 'booting' | 'network'

/**
 * 可覆盖的唤醒参数。
 * 测试通过调小 budgetMs / retryIntervalMs 让失败路径快速返回，避免用例挂起。
 */
export const wakeConfig = {
  /**
   * 单次探测超时。
   *
   * 2026-09-22 由 20s 提升到 45s：实例启动期间 Render 会把请求**挂在连接上**
   * 而不是立刻返回 502，20s 的超时会在后端就绪前反复中断，制造探测风暴且
   * 永远拿不到成功响应。给到 45s 后单次探测即可"陪跑"完最慢的一段启动。
   */
  probeTimeoutMs: 45000,
  /** 两次探测之间的间隔 */
  retryIntervalMs: 2000,
  /**
   * 唤醒总预算。
   *
   * 实测冷启动 338s / 355s，而保活工作流 480s 的预算也失败过 —— 故取 **480s（8 分钟）**
   * 作为单轮上限：宁可让用户多等（期间有实时进度，且可随时点「继续等待」追加），
   * 也不要让他在 150s / 360s 的悬崖上掉下去。
   * 注意：这是**单轮**预算，用户点「继续等待」会在此之上追加，不会重置已等待时间。
   */
  budgetMs: 480000,
  /** 探测成功后的「已知就绪」有效期：避免每次请求都先探测一次 */
  readyTtlMs: 60000,
}

/** 响应式唤醒状态，供 UI 使用 */
export const wakeState = reactive({
  status: 'idle' as WakeStatus,
  /**
   * 已等待时长（毫秒）。
   * 由 1s 心跳持续刷新（而非只在两次探测之间更新），否则 45s 的单次探测会让
   * 进度数字长时间不动，看起来像卡死。
   */
  elapsedMs: 0,
  /** 已发出的探测次数 */
  attempts: 0,
  /** 最近一次失败的原因分类（成功时为 none） */
  errorKind: 'none' as WakeErrorKind,
})

/** 单飞中的探测 Promise；非 null 表示已有调用在进行 */
let inflight: Promise<boolean> | null = null
/** 最近一次探测成功的时间戳 */
let lastReadyAt = 0
/** 本轮等待的起点；跨「失败→继续等待」保留，使已等待秒数连续累计 */
let firstAttemptAt = 0
/** 本轮等待的截止时间戳；被 ensureAwake 的后续调用延长 */
let deadlineAt = 0
/** 进度心跳定时器 */
let ticker: ReturnType<typeof setInterval> | null = null

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 后端是否处于「已知就绪」状态（短时间内探测成功过） */
export function isBackendKnownReady(): boolean {
  return wakeState.status === 'ready' && Date.now() - lastReadyAt < wakeConfig.readyTtlMs
}

function startTicker(): void {
  if (ticker) return
  ticker = setInterval(() => {
    if (wakeState.status === 'probing' && firstAttemptAt > 0) {
      wakeState.elapsedMs = Date.now() - firstAttemptAt
    }
  }, 1000)
}

function stopTicker(): void {
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

/**
 * 单次探测：命中无鉴权的轻量端点 /api/info。
 *
 * 设计约束（2026-09-19 真机回归修复，务必保持）：
 * 1. **不得携带任何非 CORS 安全列表请求头**（原实现加了 `Cache-Control: no-cache`）。
 *    跨域下 `Cache-Control` 不属于 safelisted header，会强制浏览器先发 OPTIONS 预检；
 *    而后端 `SecurityConfig.setAllowedHeaders` 白名单并不包含它 → 预检返回 403 且不带
 *    CORS 响应头 → 浏览器直接拦截真实请求。表现为「探测永远失败、唤醒器空转到预算耗尽」，
 *    并使登录流程白等满预算（比修复前更糟）。
 *    改缓存策略请用 URL 上的时间戳参数（`_t`），不要动请求头。
 * 2. 用 `validateStatus: () => true` 把「HTTP 响应」视为实例已唤醒——但**要区分
 *    响应来自后端进程还是 Render 边缘节点**：
 *      · 后端进程返回的 4xx/5xx（含业务 Result JSON）证明进程已起来 → 视为就绪；
 *      · 边缘节点在实例未就绪时返回的 502/503/504 **且响应体不是业务 JSON**（多为
 *        HTML 错误页）→ 不能算就绪，否则会把「还没起来」误判为 ready，登录 POST
 *        紧接着再吃一个 502，用户看到的是「秒失败」而不是「耐心等待」。
 * 3. 只有网络层错误（连接被拒 / 超时 / CORS 拦截）才判定为未就绪，并按
 *    {@link WakeErrorKind} 区分「还在启动」与「网络不通」。
 */
async function probeOnce(): Promise<{ ok: boolean; kind: WakeErrorKind }> {
  try {
    const res = await axios.get(`${apiBaseUrl}/api/info`, {
      timeout: wakeConfig.probeTimeoutMs,
      params: { _t: Date.now() },
      validateStatus: () => true,
    })
    const status = Number(res?.status ?? 0)
    if (status === 502 || status === 503 || status === 504) {
      const body: unknown = res?.data
      const isBusinessJson =
        body != null && typeof body === 'object' && 'code' in (body as Record<string, unknown>)
      if (!isBusinessJson) return { ok: false, kind: 'booting' }
    }
    return { ok: true, kind: 'none' }
  } catch (e) {
    const err = e as { code?: string; response?: unknown }
    // 有响应体却仍进 catch：属于响应解析层异常，实例显然是活的
    if (err?.response) return { ok: true, kind: 'none' }
    // 本地超时 = 连接被后端/边缘节点挂起 —— 最典型的「正在启动」信号
    if (err?.code === 'ECONNABORTED') return { ok: false, kind: 'booting' }
    return { ok: false, kind: 'network' }
  }
}

/**
 * 确保后端已唤醒。多个并发调用共享同一次探测（单飞）。
 *
 * 若探测**已经在进行中**，本次调用不会新起一轮，而是把当前这轮的截止时间往后延
 * （取两者较晚者）。这样「用户点了继续等待」不会把已等待的 3 分钟清零重来。
 *
 * @param budgetMs 本次调用要求的唤醒预算，默认 {@link wakeConfig.budgetMs}
 * @returns 后端是否在预算内就绪
 */
export function ensureAwake(budgetMs: number = wakeConfig.budgetMs): Promise<boolean> {
  if (isBackendKnownReady()) {
    return Promise.resolve(true)
  }

  const now = Date.now()

  if (inflight) {
    deadlineAt = Math.max(deadlineAt, now + budgetMs)
    return inflight
  }

  // 失败后再次调用视为「继续等待」：保留起点，让已等待秒数连续累计
  if (wakeState.status !== 'failed') {
    firstAttemptAt = now
  }
  deadlineAt = now + budgetMs
  wakeState.status = 'probing'
  wakeState.attempts = 0
  wakeState.errorKind = 'none'
  startTicker()

  inflight = (async () => {
    let ok = false
    let kind: WakeErrorKind = 'network'
    for (;;) {
      const r = await probeOnce()
      ok = r.ok
      kind = r.kind
      wakeState.attempts += 1
      wakeState.elapsedMs = Date.now() - firstAttemptAt
      if (ok) break
      if (Date.now() >= deadlineAt) break
      await sleep(wakeConfig.retryIntervalMs)
      // 注意：deadlineAt 可能在 sleep 期间被其他调用方延长，故每轮都重新判断
    }
    stopTicker()
    if (ok) {
      lastReadyAt = Date.now()
      wakeState.status = 'ready'
      wakeState.errorKind = 'none'
    } else {
      lastReadyAt = 0
      wakeState.status = 'failed'
      wakeState.errorKind = kind
    }
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
 * 用户在登录页输入账号密码的时间通常有数十秒，足以覆盖一部分冷启动耗时。
 */
export function prewarmBackend(): void {
  void ensureAwake().catch(() => undefined)
}

/**
 * 安装唤醒恢复钩子（幂等）：
 * - 页面重新可见（用户切回标签页/解锁手机）：若尚未就绪且当前无探测在跑，补一次
 * - 网络恢复（online 事件）：同上
 *
 * 移动端浏览器会冻结后台标签页的定时器，用户切走再切回时探测可能已"停摆"，
 * 没有这个钩子就会一直停在「正在冷启动」的假象上。
 */
let recoveryInstalled = false
export function installWakeRecovery(): void {
  if (recoveryInstalled) return
  recoveryInstalled = true
  const retryIfNeeded = () => {
    if (!isBackendKnownReady() && !inflight) {
      void ensureAwake().catch(() => undefined)
    }
  }
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') retryIfNeeded()
    })
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('online', retryIfNeeded)
  }
}

/** 仅供测试重置内部状态 */
export function __resetWakeStateForTest(): void {
  stopTicker()
  inflight = null
  lastReadyAt = 0
  firstAttemptAt = 0
  deadlineAt = 0
  wakeState.status = 'idle'
  wakeState.elapsedMs = 0
  wakeState.attempts = 0
  wakeState.errorKind = 'none'
}

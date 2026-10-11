import { clearAuth, isTokenValid } from '../auth'
import { ElMessage } from 'element-plus'
import {
  LOGIN_PATH,
  SESSION_EXPIRED_FLAG,
  SESSION_EXPIRED_MESSAGE,
  buildSessionExpiredLoginUrl,
} from './sessionExpiry'

/**
 * 会话失效恢复守卫（v1.66.18）
 *
 * ── 线上问题 ─────────────────────────────────────────────────────
 * 用户「登录后不关页面、隔一段时间再回到该页面」→ **所有功能都不可用**，
 * 而页面仍停在「看起来已登录」的状态，且被弹回登录页时**没有任何说明**。
 *
 * ── 触发条件（已核实，勿再沿用错误结论）───────────────────────────
 * ⚠️ 本模块**不是**为「隔几小时」设计的。线上 `app.jwt.expiration-ms` 实测为
 * 86400000（24h，`render.yaml` 与 `application.yml` 默认值一致，并已用线上签发的
 * 真实 token 解码复核：`exp - iat = 86400s`）。因此「中午登录 → 下午回来」这种
 * 同日内几小时的间隔，token **不可能**过期 —— 之前把该现象归因为「凭证有效期到了」
 * 是错的，别再写回注释与更新日志。
 *
 * 本模块真正兜住的是「页面长期开着，会话在**页面之外**失效」这一类：
 * 1. 离开超过 24h（token 自然过期）；
 * 2. 服务端主动失效：在别的标签页/设备登出（`jti` 进黑名单）、`JWT_SECRET` 轮换、
 *    账号被禁用 —— 此时**本地 token 看着完全合法**，只有服务端会拒绝；
 * 3. 浏览器清掉 / 逐出 localStorage，而内存里的 `authState` 仍是旧值。
 *
 * 为什么此前所有兜底都没兜住：
 * - `auth.ts` 只在**应用启动时**清理一次失效 token（模块顶层），页面不重新加载
 *   就不会再执行；
 * - `api/index.ts` 的请求拦截器 / 响应拦截器确实会处理失效 token，
 *   但**前提是有请求发出去**。用户回到页面往往只是「看一眼」，
 *   没触发任何请求 → 拦截器不执行 → 页面停在假死态；
 * - `router/index.ts` 的 `beforeEach` 只在**路由跳转**时校验，
 *   不跳转就不校验。
 *
 * 三条路径都依赖「某个动作」才触发，而「回到页面」本身不是这些动作。
 * 因此需要在应用层新增一个**主动**信号：页面重新可见 / 窗口重新聚焦时，
 * 立即重新校验 token，失效则清会话并带 redirect 跳登录，并给出明确提示。
 *
 * ── 与既有模块的边界 ───────────────────────────────────────────────
 * - `utils/backendWake.ts` 的 `installWakeRecovery()` 也在监听 visibilitychange，
 *   但它只负责「补一次后端冷启动探测」，**不碰 token / 登录态**，两者互不冲突；
 * - `appLaunch.ts` 的 `createHandoffWatcher` 监听的是「页面被切走」方向
 *   （hidden / pagehide / blur），本模块监听的是反方向（visible / focus），
 *   信号方向相反，不会互相误触。
 *
 * ── 可测性 ─────────────────────────────────────────────────────────
 * 决策逻辑抽成纯函数 {@link evaluateSessionRecovery}（不依赖 DOM），
 * 监听器通过 {@link installSessionGuard} 注入，便于单测。
 */

// 统一约定定义在无依赖的 `sessionExpiry.ts`（api 拦截器也要用，见该文件说明）。
// 这里原样再导出，保持本模块既有的公开接口不变。
export { LOGIN_PATH, SESSION_EXPIRED_FLAG, SESSION_EXPIRED_MESSAGE, buildSessionExpiredLoginUrl }

/** 会话恢复评估结果 */
export type SessionRecoveryAction = 'none' | 'redirect-login'

export interface SessionRecoveryDecision {
  /** 需要执行的动作 */
  action: SessionRecoveryAction
  /** action === 'redirect-login' 时的回跳地址（含 redirect 查询串） */
  redirectTo?: string
  /** 判定原因，便于日志与测试断言 */
  reason: 'valid' | 'on-login-page' | 'expired'
}

/**
 * 评估「页面恢复可见」时是否需要处理过期会话。
 *
 * 纯函数：只依赖传入的 token 与当前路径，不读 localStorage、不碰 window，
 * 因此可在 node 环境直接单测。
 *
 * @param token    当前持有的 token（可为空串 / null）
 * @param pathname 当前路径（如 `/resume`）
 * @param redirect 过期时要在 redirect 参数里带回的相对地址，默认取 pathname
 */
export function evaluateSessionRecovery(
  token: string | null | undefined,
  pathname: string,
  redirect?: string,
): SessionRecoveryDecision {
  // 已在登录页：无论 token 状态如何都不再跳转（防循环）
  if (pathname === LOGIN_PATH) {
    return { action: 'none', reason: 'on-login-page' }
  }
  // token 合法且未过期：无需处理
  if (token && isTokenValid(token)) {
    return { action: 'none', reason: 'valid' }
  }
  // token 缺失 / 格式非法 / 已过期 → 清会话并带 redirect 跳登录。
  // 跳转地址统一由 `sessionExpiry.buildSessionExpiredLoginUrl` 构造（带 `expired=1`），
  // 与 api 拦截器的 401 兜底共用同一约定，避免两处各写一遍、改一处漏一处。
  const target = redirect && redirect.length > 0 ? redirect : pathname
  return {
    action: 'redirect-login',
    redirectTo: buildSessionExpiredLoginUrl(target),
    reason: 'expired',
  }
}

/** 需要跳转时的副作用依赖，便于单测注入 */
export interface SessionGuardHooks {
  /** 当前路径 */
  getPathname?: () => string
  /** 当前页面相对地址（含查询串），用于登录后回跳 */
  getRedirectTarget?: () => string
  /** 读取当前 token */
  getToken?: () => string | null
  /** 清除会话 */
  onClear?: () => void
  /** 跳转登录 */
  onRedirect?: (href: string) => void
  /** 提示用户 */
  onNotify?: (message: string) => void
  /** 最小化 DOM 依赖，便于测试注入假实现 */
  doc?: Pick<Document, 'addEventListener' | 'removeEventListener' | 'visibilityState'> | null
  win?: Pick<Window, 'addEventListener' | 'removeEventListener'> | null
}

/**
 * 处理一次「页面恢复可见 / 聚焦」事件。
 *
 * 返回本次判定的决策（供调用方与测试断言）。副作用（清 auth / 提示 / 跳转）
 * 仅在决策为 redirect-login 时发生。
 *
 * @returns 本次的 {@link SessionRecoveryDecision}
 */
export function handleSessionRecovery(hooks: SessionGuardHooks = {}): SessionRecoveryDecision {
  const getToken = hooks.getToken ?? (() => localStorage.getItem('token'))
  const getPathname = hooks.getPathname ?? (() => (typeof window === 'undefined' ? '' : window.location.pathname))
  const getRedirectTarget =
    hooks.getRedirectTarget ??
    (() =>
      typeof window === 'undefined' ? '' : window.location.pathname + window.location.search)
  const onClear = hooks.onClear ?? clearAuth
  const onRedirect = hooks.onRedirect ?? ((href: string) => { window.location.href = href })
  // 默认用 Element Plus 的 warning 提示；跳转是同步的，消息实例会随页面卸载前展示一次
  const onNotify = hooks.onNotify ?? ((message: string) => { ElMessage.warning(message) })

  const pathname = getPathname()
  const decision = evaluateSessionRecovery(getToken(), pathname, getRedirectTarget())

  if (decision.action === 'redirect-login' && decision.redirectTo) {
    onClear()
    // 提示的真正送达由登录页完成（读 URL 上的 SESSION_EXPIRED_FLAG）。
    // 这里的 onNotify 只是「尽力而为」：若跳转被推迟（标签页仍被节流），
    // 用户至少有机会看到一句说明。默认实现交给调用方，避免无谓地引入 UI 依赖。
    if (hooks.onNotify) onNotify(SESSION_EXPIRED_MESSAGE)
    onRedirect(decision.redirectTo)
  }

  return decision
}

/** 幂等安装标记，避免热更新 / 重复调用时叠加监听器 */
let installed = false

/**
 * 安装「会话过期恢复」守卫（幂等）。
 *
 * 监听两个「页面回到用户面前」信号：
 * - `document.visibilitychange` 且 `visibilityState === 'visible'`（切回标签页 / 解锁手机）
 * - `window.focus`（窗口重新获得焦点；部分浏览器只发这一个）
 *
 * 命中时立即校验 token：过期 / 非法则清会话、提示并跳登录（带 redirect）。
 * 登录页自身不触发跳转（由 {@link evaluateSessionRecovery} 保证）。
 */
export function installSessionGuard(): void {
  if (installed) return
  installed = true

  const doc = typeof document === 'undefined' ? null : document
  const win = typeof window === 'undefined' ? null : window

  const onVisible = () => {
    if (doc && doc.visibilityState === 'visible') {
      handleSessionRecovery()
    }
  }
  const onFocus = () => {
    handleSessionRecovery()
  }

  if (doc?.addEventListener) {
    doc.addEventListener('visibilitychange', onVisible)
  }
  if (win?.addEventListener) {
    win.addEventListener('focus', onFocus)
  }
}

/** 仅供测试重置安装状态 */
export function __resetSessionGuardForTest(): void {
  installed = false
}

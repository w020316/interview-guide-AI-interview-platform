/**
 * 登出请求配置（P1-03，2026-10-30 质量循环）。
 *
 * ── 为什么需要这个函数 ──────────────────────────────────────────────
 * 登出的语义是「让服务端吊销当前 token」（后端把 token_version +1，全设备失效）。
 * 但原实现是：
 *
 *     api.post('/api/auth/logout').catch(() => {})   // 异步：拦截器在微任务里读 localStorage
 *     clearAuth()                                    // 同步：先把 token 删了
 *
 * 请求拦截器读不到 token → 请求不带 Authorization → 后端取不到 userId
 * → 直接返回成功但**什么都不做**。结果：点完「退出登录」，JWT 仍可被复用直到自然过期（24h）。
 *
 * 修复方式不是调整时序（仍可能因微任务顺序出错），而是**显式把旧 token 放进请求头**：
 * 请求发出后再清本地状态，服务端一定拿得到身份。
 *
 * ── 为什么单独抽成纯函数 ──────────────────────────────────────────
 * App.vue 的登出逻辑依赖 router / axios 实例，难以在单测里隔离；
 * 这里只把「token → 请求头」这一段可验证的契约抽出来，行为可断言、可回归。
 */

export interface LogoutRequestConfig {
  headers: Record<string, string>
}

/**
 * 由当前 token 构造登出请求配置。
 *
 * @param token 登出前持有的 JWT；空/无效时返回 undefined（匿名登出，后端幂等成功）
 */
export function logoutRequestConfig(
  token: string | null | undefined,
): LogoutRequestConfig | undefined {
  const t = (token || '').trim()
  if (!t) return undefined
  return { headers: { Authorization: `Bearer ${t}` } }
}

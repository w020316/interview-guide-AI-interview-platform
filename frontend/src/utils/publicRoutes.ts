/**
 * 「当前路由是否需要登录」的轻量判定（P1-02，2026-10-30 质量循环）。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * 会话恢复守卫此前只判断「token 是否有效」，不判断「当前页是否需要登录」。
 * 于是**未登录访客**打开首页 `/`（或任意 404 页）→ 切到别的标签页 → 切回来
 * 就被跳到 `/login?expired=1&redirect=/`，还提示「登录已过期」。
 * 对「求职者路过 → 注册」的产品，这是直接的流失点，且错误归因会让人以为系统有问题。
 *
 * ── 为什么不用 vue-router 的 meta ───────────────────────────────────
 * 守卫的判定逻辑是**纯函数**（`sessionGuard.ts` 要能在 node 环境直接单测），
 * 引入 vue-router 会把「需要路由实例」的依赖带进来。这里用一份与 `router/index.ts`
 * 同步维护的路径清单：只有明确列出的业务路径才算「需要登录」，
 * **未匹配的路径（404）自然落在公开侧** —— 这正是我们要的行为。
 *
 * ⚠️ 新增业务路由时必须同步这里；`publicRoutes.test.ts` 会逐条核对主要路径。
 */

/** 需要登录的业务路径（精确匹配该路径本身，或作为子路径前缀） */
const PROTECTED_BASES: readonly string[] = [
  '/resume',
  '/job',
  '/jobs',
  '/agent',
  '/career',
  '/salary',
  '/applications',
  '/interview',
  '/history',
  '/learning',
  '/calendar',
  '/wrong-book',
  '/favorites',
  '/progress',
  '/profile',
  '/knowledge',
  '/admin',
]

/** 公开的业务子路径（形如 `/jobs/123` 的岗位详情，分享链接要能直接被打开） */
const PUBLIC_PREFIXES: readonly string[] = ['/jobs/']

/** 去掉末尾斜杠（`/resume/` 与 `/resume` 等价） */
function normalize(pathname: string): string {
  if (!pathname) return ''
  return pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname
}

/**
 * 该路径是否需要登录。
 *
 * @param pathname `window.location.pathname`（不含查询串）
 */
export function isProtectedRoute(pathname: string): boolean {
  const path = normalize(pathname || '')
  if (PUBLIC_PREFIXES.some((p) => path.startsWith(p))) return false
  return PROTECTED_BASES.some((base) => path === base || path.startsWith(base + '/'))
}

/** 该路径是否公开（`!isProtectedRoute`，命名版便于阅读） */
export function isPublicRoute(pathname: string): boolean {
  return !isProtectedRoute(pathname)
}

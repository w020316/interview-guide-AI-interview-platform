/**
 * 「会话失效 → 回登录页」的统一约定（v1.66.18）
 *
 * ── 为什么单独抽成一个**无依赖**模块 ──────────────────────────────────
 * 这套约定要被两条完全不同的链路共用：
 * 1. `utils/sessionGuard.ts` —— 页面从后台恢复可见时的**主动**校验；
 * 2. `api/index.ts` 的请求/响应拦截器 —— 请求发出后的**被动**兜底（401 / token 本地已过期）。
 *
 * 若把常量与构造函数放在 `sessionGuard.ts` 里，`api/index.ts` 就会间接
 * `import 'element-plus'`（sessionGuard 顶层 import 了 ElMessage）—— 而 `api`
 * 被所有视图依赖，这会把 element-plus 从懒加载分块里拽进主包。因此这里刻意
 * **不引入任何依赖**（连 `auth.ts` 都不引，避免与拦截器形成环）。
 *
 * ── 为什么必须带 `expired=1` ────────────────────────────────────────
 * 跳登录是**整页导航**，跳转前弹的 toast 挂在即将被卸载的 DOM 上，用户很可能
 * 根本看不到 —— 结果就是「莫名其妙回到登录页」，比故障本身更像故障。
 * 因此统一改成：URL 携带标记，由登录页在自身渲染上下文里展示一条持久提示，
 * 让「为什么回到登录页」永远有解释。
 */

/** 登录页路径：在此路径下不再跳转，避免「跳登录 → 又检测到失效 → 再跳」的死循环 */
export const LOGIN_PATH = '/login'

/** 会话失效标记：挂在 URL 上（`/login?expired=1`），由登录页读取后展示持久提示 */
export const SESSION_EXPIRED_FLAG = 'expired'

/** 会话失效提示文案（用户可读，不含技术术语） */
export const SESSION_EXPIRED_MESSAGE = '登录已过期，请重新登录'

/**
 * 构造「会话失效」登录页地址。
 *
 * @param redirect 登录成功后的回跳地址（相对路径，可含查询串）。
 *                 传空 / 省略时只带 `expired=1`，登录后回首页。
 */
export function buildSessionExpiredLoginUrl(redirect?: string): string {
  const params = new URLSearchParams()
  params.set(SESSION_EXPIRED_FLAG, '1')
  if (redirect && redirect.length > 0) {
    params.set('redirect', redirect)
  }
  return `${LOGIN_PATH}?${params.toString()}`
}

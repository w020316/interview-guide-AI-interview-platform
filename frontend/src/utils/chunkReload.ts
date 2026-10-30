/**
 * 代码分块加载失败的一次性自动刷新（P2-18，2026-10-30 质量循环）。
 *
 * ── 要解决的缺陷 ────────────────────────────────────────────────────
 * `router.onError` 里对 `Failed to fetch dynamically imported module` **无条件**
 * `location.reload()`。CDN 持续故障或用户离线时，刷新后仍取不到 chunk → 再触发
 * `router.onError` → 再刷新 …… 页面进入无限刷新循环，用户连一句提示都看不到。
 *
 * ── 策略 ────────────────────────────────────────────────────────────
 * 每个浏览器会话只自动刷新一次（`sessionStorage` 标记），二次失败改为**明确提示**
 * 并交给用户手动刷新。标记在成功导航（`router.afterEach`）后清除，
 * 这样「换了个页面就好了」之后再次遇到网络抖动，仍能享受一次自动恢复。
 *
 * <p>sessionStorage 不可用（隐私模式/被禁用）时**不自动刷新**：无法保证只刷一次，
 * 宁可显示错误也不能把用户拖进循环。
 */

const KEY = 'chunk-reload-attempted'

/**
 * 本次分块加载失败是否应该自动刷新。
 *
 * @returns true = 允许刷新一次（已写入标记）；false = 已刷过一次或无法记录标记，应提示用户
 */
export function shouldAutoReloadForChunkError(): boolean {
  try {
    if (sessionStorage.getItem(KEY)) return false
    sessionStorage.setItem(KEY, '1')
    return true
  } catch {
    return false
  }
}

/** 成功导航后清除标记 */
export function clearChunkReloadFlag(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // 不可用时无需清理
  }
}

/**
 * 外链协议白名单（P1-04，2026-10-30 质量循环）。
 *
 * ── 为什么前端也要做一遍 ────────────────────────────────────────────
 * 后端已在入库/下发时过滤（`UrlSafety`），但前端仍必须自带这一层：
 *   1. 浏览器扩展 / 历史数据库 / 手工改库都可能让旧数据带上 `javascript:`；
 *   2. 前端 `:href` 与 `window.open` 是**最终执行点**，依赖上游过滤等于把 XSS 的
 *      最后一道门交给别人；
 *   3. 本项目 JWT 存在 localStorage —— 一个 `javascript:` 链接就能在应用自身源
 *      读走 token，这不是「理论上可能」，是几分钟就能写完的攻击。
 *
 * ── 规则 ────────────────────────────────────────────────────────────
 * 只放行 `http:` / `https:` 且带 host 的绝对链接；其余（`javascript:`、`data:`、
 * `blob:`、`vbscript:`、协议相对 `//evil`、含控制字符、超长）一律返回 null。
 * 调用方按「没有链接」渲染，不要给用户一个点了没反应的按钮。
 */

/** 与后端 `UrlSafety.MAX_LENGTH` / 列宽一致 */
const MAX_LENGTH = 500

/**
 * 校验并返回可安全渲染的外链。
 *
 * @returns 合法时返回 trim 后的原字符串；非法返回 null
 */
export function safeExternalUrl(url: string | null | undefined): string | null {
  const value = (url || '').trim()
  if (!value || value.length > MAX_LENGTH) return null
  // 控制字符可被用来构造 `java\nscript:` 这类绕过（不同解析器行为不一致）
  if (/[\u0000-\u001f\u007f]/.test(value)) return null

  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
  if (!parsed.hostname) return null
  return value
}

/** 是否可安全渲染/跳转（模板里做 v-if 判据用） */
export function isSafeExternalUrl(url: string | null | undefined): boolean {
  return safeExternalUrl(url) !== null
}

/**
 * 登录后跳转地址的安全收敛（开放重定向防护）。
 *
 * <p>只接受**站内绝对路径**。此前只判断 {@code r.startsWith('/')}，
 * 而浏览器把 `//evil.com` 解析为协议相对地址、把 `/\evil.com` 里的反斜杠
 * 当成斜杠——两者都能通过那个判断，跳到站外。
 *
 * <p>因此这里做三重校验：字面前缀 → URL 解析后的 origin → 只回传 path/search/hash。
 * 任何一步不合规都回退到首页，**不抛错**（跳转逻辑不该让登录流程失败）。
 */
export function safeRedirect(raw: unknown, origin: string): string {
  const r = Array.isArray(raw) ? raw[0] : raw
  if (typeof r !== 'string' || r.length === 0) return '/'
  // 反斜杠在特殊协议下等价于斜杠，必须与 '//' 同等对待
  if (!r.startsWith('/') || r.startsWith('//') || r.startsWith('/\\')) return '/'
  try {
    const url = new URL(r, origin)
    if (url.origin !== origin) return '/'
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return '/'
  }
}

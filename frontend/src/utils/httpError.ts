/**
 * HTTP 错误判别（单一来源）。
 *
 * <p><b>为什么必须按 HTTP 状态码而不是 body 的 `code`</b>：
 * `src/api/index.ts` 的响应拦截器对「业务错误」（HTTP 200 + body `code !== 200`）
 * 会 reject 成 `new Error(data.message)` —— **body 的 `code` 在这一层被丢掉了**；
 * 只有真正的 HTTP 状态错误（如 409）才会保留 `error.response.status`。
 * 因此凡是要「按状态码分流」的处理（如冲突提示），一律走本文件，
 * 不要在视图里各写一份 `as { response?: { status?: number } }` 强转 —— 那是同一口径散落多处。
 */

/** 是否为 HTTP 409（冲突：资源已存在 / 数据已被改动 / 乐观锁失败） */
export function isConflictError(e: unknown): boolean {
  return (e as { response?: { status?: number } } | null)?.response?.status === 409
}

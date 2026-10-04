import { describe, expect, it } from 'vitest'
import { isConflictError } from './httpError'

/**
 * 单测目的：锁定「409 判定只看 HTTP 状态码」这一条 —— 它是对外契约的可测面。
 *
 * 为什么不能靠 body 的 `code`：`api/index.ts` 的响应拦截器对
 * 「HTTP 200 + body code≠200」会 reject 成 `new Error(message)`，**丢掉 code**；
 * 只有真正的 HTTP 状态错误才保留 `error.response.status`。
 */
describe('isConflictError', () => {
  it('HTTP 409 → true', () => {
    expect(isConflictError({ response: { status: 409 } })).toBe(true)
  })

  it('其他状态码 → false（400/404/500/403 都不是冲突）', () => {
    for (const status of [200, 400, 401, 403, 404, 500, 503]) {
      expect(isConflictError({ response: { status } })).toBe(false)
    }
  })

  it('非 HTTP 形状（普通 Error / null / undefined / 字符串）一律 false，不抛异常', () => {
    expect(isConflictError(new Error('boom'))).toBe(false)
    expect(isConflictError(null)).toBe(false)
    expect(isConflictError(undefined)).toBe(false)
    expect(isConflictError('409')).toBe(false)
    expect(isConflictError({ status: 409 })).toBe(false) // 缺 response 包装
  })

  it('response 存在但没有 status（网络中断）→ false', () => {
    expect(isConflictError({ response: {} })).toBe(false)
  })
})

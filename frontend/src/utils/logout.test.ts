import { describe, expect, it } from 'vitest'
import { logoutRequestConfig } from './logout'

describe('logoutRequestConfig（P1-03）', () => {
  it('有 token 时显式带上 Authorization 头（登出请求不能再依赖拦截器）', () => {
    const cfg = logoutRequestConfig('header.payload.signature')
    expect(cfg).toEqual({ headers: { Authorization: 'Bearer header.payload.signature' } })
  })

  it('无 token / 空白 token 时返回 undefined（匿名登出由后端幂等处理）', () => {
    expect(logoutRequestConfig(null)).toBeUndefined()
    expect(logoutRequestConfig('')).toBeUndefined()
    expect(logoutRequestConfig('   ')).toBeUndefined()
  })
})

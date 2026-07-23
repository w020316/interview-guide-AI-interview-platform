import { describe, it, expect, beforeEach } from 'vitest'
import { isValidJwt, isTokenExpired, isTokenValid } from './auth'

/**
 * 生成测试用 JWT（三段式，payload 可自定义）
 * 仅用于测试，不签名
 */
function makeMockJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const body = btoa(JSON.stringify(payload))
  const sig = 'test-signature'
  return `${header}.${body}.${sig}`
}

describe('auth', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  describe('isValidJwt', () => {
    it('三段式合法 token 返回 true（长度 > 20）', () => {
      expect(isValidJwt('aaaaaaaaaa.bbbbbbbbbb.cccccccccc')).toBe(true)
    })

    it('长度 < 20 返回 false', () => {
      expect(isValidJwt('a.b.c')).toBe(false)
    })

    it('非字符串返回 false', () => {
      expect(isValidJwt(null as unknown as string)).toBe(false)
      expect(isValidJwt(undefined as unknown as string)).toBe(false)
    })

    it('非三段式返回 false', () => {
      expect(isValidJwt('not.a.jwt.extra')).toBe(false)
      expect(isValidJwt('nojwt')).toBe(false)
    })
  })

  describe('isTokenExpired', () => {
    it('无 exp 字段视为永久有效，返回 false', () => {
      const token = makeMockJwt({ sub: '1' })
      expect(isTokenExpired(token)).toBe(false)
    })

    it('exp 已过期返回 true', () => {
      const token = makeMockJwt({ sub: '1', exp: Math.floor(Date.now() / 1000) - 3600 })
      expect(isTokenExpired(token)).toBe(true)
    })

    it('exp 未过期返回 false', () => {
      const token = makeMockJwt({ sub: '1', exp: Math.floor(Date.now() / 1000) + 3600 })
      expect(isTokenExpired(token)).toBe(false)
    })

    it('非法 token 返回 true', () => {
      expect(isTokenExpired('invalid')).toBe(true)
    })
  })

  describe('isTokenValid', () => {
    it('合法格式 + 未过期返回 true', () => {
      const token = makeMockJwt({ sub: '1', exp: Math.floor(Date.now() / 1000) + 3600 })
      expect(isTokenValid(token)).toBe(true)
    })

    it('合法格式 + 已过期返回 false', () => {
      const token = makeMockJwt({ sub: '1', exp: Math.floor(Date.now() / 1000) - 3600 })
      expect(isTokenValid(token)).toBe(false)
    })

    it('非法格式返回 false', () => {
      expect(isTokenValid('invalid')).toBe(false)
    })
  })
})

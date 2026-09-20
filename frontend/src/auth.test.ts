import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  isValidJwt,
  isTokenExpired,
  isTokenValid,
  authState,
  setAuth,
  clearAuth,
  isLoggedIn,
  isAdmin,
} from './auth'

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
    it('P2-23：无 exp 字段视为无效，返回 true（不再永久有效）', () => {
      const token = makeMockJwt({ sub: '1' })
      expect(isTokenExpired(token)).toBe(true)
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

  describe('setAuth / clearAuth / isLoggedIn / isAdmin', () => {
    const validToken = (role?: string) =>
      makeMockJwt({
        sub: '1',
        exp: Math.floor(Date.now() / 1000) + 3600,
        ...(role ? { role } : {}),
      })

    beforeEach(() => {
      // 每个用例前重置响应式状态，避免单例串扰
      clearAuth()
      localStorage.clear()
    })

    it('setAuth 持久化 token 并更新响应式状态', () => {
      const token = validToken()
      setAuth(token, 'alice')
      expect(authState.token).toBe(token)
      expect(authState.username).toBe('alice')
      expect(localStorage.getItem('token')).toBe(token)
      expect(localStorage.getItem('username')).toBe('alice')
    })

    it('setAuth 拒绝非 JWT 格式 token（不污染状态/存储）', () => {
      setAuth('not-a-jwt')
      expect(authState.token).toBe('')
      expect(localStorage.getItem('token')).toBeNull()
    })

    it('clearAuth 清空内存与存储', () => {
      setAuth(validToken(), 'bob')
      clearAuth()
      expect(authState.token).toBe('')
      expect(authState.username).toBe('')
      expect(localStorage.getItem('token')).toBeNull()
      expect(localStorage.getItem('username')).toBeNull()
    })

    it('isLoggedIn 跟随 token 有效性', () => {
      expect(isLoggedIn()).toBe(false)
      setAuth(validToken())
      expect(isLoggedIn()).toBe(true)
      clearAuth()
      expect(isLoggedIn()).toBe(false)
    })

    it('isAdmin 解析 role claim', () => {
      setAuth(validToken('ROLE_ADMIN'))
      expect(isAdmin()).toBe(true)
      clearAuth()
      setAuth(validToken('ROLE_USER'))
      expect(isAdmin()).toBe(false)
    })

    it('持久化失败时仍更新内存状态且不抛错', () => {
      const spy = vi
        .spyOn(Storage.prototype, 'setItem')
        .mockImplementation(() => {
          throw new Error('quota')
        })
      const token = validToken('ROLE_ADMIN')
      expect(() => setAuth(token, 'carol')).not.toThrow()
      // 内存状态仍正确更新，仅存储层面失败
      expect(authState.token).toBe(token)
      expect(authState.username).toBe('carol')
      expect(isLoggedIn()).toBe(true)
      expect(isAdmin()).toBe(true)
      spy.mockRestore()
    })

    it('模块初始化时存储读取失败不抛错，状态安全回退为空', async () => {
      const spy = vi
        .spyOn(Storage.prototype, 'getItem')
        .mockImplementation(() => {
          throw new Error('blocked')
        })
      // 重新加载模块：启动期 loadValidToken/loadUsername/清理逻辑均会经历读存储抛错
      vi.resetModules()
      const mod = await import('./auth')
      expect(mod.authState.token).toBe('')
      expect(mod.authState.username).toBe('')
      spy.mockRestore()
    })
  })
})

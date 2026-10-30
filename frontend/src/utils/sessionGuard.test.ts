import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  evaluateSessionRecovery,
  handleSessionRecovery,
  installSessionGuard,
  __resetSessionGuardForTest,
  LOGIN_PATH,
  SESSION_EXPIRED_FLAG,
} from './sessionGuard'
import { setAuth, clearAuth, authState } from '../auth'

/**
 * 用例来源：线上「中午登录、不关页面，下午切回该标签页 → 全部功能不可用」。
 * 三条既有兜底路径（启动清理 / 请求拦截器 / 路由守卫）都依赖「某个动作」触发，
 * 而「从后台切回来」本身不是这些动作 → 需要本模块主动校验。
 */

/** 构造一个未过期的合法 JWT（3 段式，payload 含未来 exp） */
function validToken(): string {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))
  return 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.' + payload + '.c2lnbmF0dXJldmFsdWUxMjM0NQ'
}

/** 构造一个已过期的合法 JWT */
function expiredToken(): string {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) - 60 }))
  return 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.' + payload + '.c2lnbmF0dXJldmFsdWUxMjM0NQ'
}

describe('utils/sessionGuard · evaluateSessionRecovery（纯函数）', () => {
  it('token 有效 → 不跳转', () => {
    const d = evaluateSessionRecovery(validToken(), '/resume')
    expect(d.action).toBe('none')
    expect(d.reason).toBe('valid')
  })

  it('token 已过期 → 清会话并跳登录（带 redirect）', () => {
    const d = evaluateSessionRecovery(expiredToken(), '/resume')
    expect(d.action).toBe('redirect-login')
    expect(d.reason).toBe('expired')
    expect(d.redirectTo).toBe(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=${encodeURIComponent('/resume')}`)
  })

  it('token 为空 → 跳登录', () => {
    const d = evaluateSessionRecovery('', '/resume')
    expect(d.action).toBe('redirect-login')
    expect(d.reason).toBe('expired')
  })

  it('token 格式非法 → 跳登录', () => {
    const d = evaluateSessionRecovery('not-a-jwt', '/job')
    expect(d.action).toBe('redirect-login')
  })

  it('无 exp 字段的 token 视为无效 → 跳登录', () => {
    const payload = btoa(JSON.stringify({ sub: 'u1' }))
    const noExp = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.' + payload + '.c2lnbmF0dXJldmFsdWUxMjM0NQ'
    expect(evaluateSessionRecovery(noExp, '/resume').action).toBe('redirect-login')
  })

  it('已在登录页 → 不跳转（无论 token 是否过期，避免循环）', () => {
    expect(evaluateSessionRecovery(expiredToken(), LOGIN_PATH).action).toBe('none')
    expect(evaluateSessionRecovery(expiredToken(), LOGIN_PATH).reason).toBe('on-login-page')
    expect(evaluateSessionRecovery('', LOGIN_PATH).action).toBe('none')
    expect(evaluateSessionRecovery(validToken(), LOGIN_PATH).action).toBe('none')
  })

  it('redirect 参数携带查询串（登录后能回到原页面）', () => {
    const d = evaluateSessionRecovery(expiredToken(), '/jobs', '/jobs?type=campus&page=2')
    expect(d.redirectTo).toBe(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=${encodeURIComponent('/jobs?type=campus&page=2')}`)
  })

  it('redirect 传空串时回退到 pathname', () => {
    const d = evaluateSessionRecovery(expiredToken(), '/history', '')
    expect(d.redirectTo).toBe(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=${encodeURIComponent('/history')}`)
  })

  it('跳转 URL 携带 expired 标记（供登录页展示「登录已过期」提示）', () => {
    const d = evaluateSessionRecovery(expiredToken(), '/resume')
    expect(d.redirectTo).toContain(`${SESSION_EXPIRED_FLAG}=1`)
  })

  it('token 有效时绝不带 expired 标记（避免正常登录页误显示过期提示）', () => {
    const d = evaluateSessionRecovery(validToken(), '/resume')
    expect(d.action).toBe('none')
    expect(d.redirectTo).toBeUndefined()
  })

  /**
   * P1-02（2026-10-30）：守卫必须看「当前路由是否需要登录」。
   * 原实现只判 token 有效性 → 匿名访客打开首页/404 页后切回标签页就被踢到登录页，
   * 还提示「登录已过期」（错误归因）。
   */
  describe('公开路由（P1-02）', () => {
    it('匿名访客在首页 → 什么都不做', () => {
      const d = evaluateSessionRecovery('', '/')
      expect(d.action).toBe('none')
      expect(d.reason).toBe('public-route')
      expect(d.redirectTo).toBeUndefined()
    })

    it('匿名访客在 404 页 → 什么都不做', () => {
      const d = evaluateSessionRecovery('', '/no-such-page')
      expect(d.action).toBe('none')
      expect(d.reason).toBe('public-route')
    })

    it('公开页上带着失效 token → 只静默清理，不跳转（不提示「过期」）', () => {
      const d = evaluateSessionRecovery(expiredToken(), '/')
      expect(d.action).toBe('clear-and-stay')
      expect(d.redirectTo).toBeUndefined()
    })

    it('受保护页没有 token → 仍然跳登录', () => {
      const d = evaluateSessionRecovery('', '/resume')
      expect(d.action).toBe('redirect-login')
      expect(d.redirectTo).toContain('redirect=')
    })

    it('调用方可注入自定义公开性判定（保持纯函数不依赖路由表）', () => {
      const d = evaluateSessionRecovery('', '/whatever', undefined, () => true)
      expect(d.action).toBe('none')
      expect(d.reason).toBe('public-route')
    })
  })
})

describe('utils/sessionGuard · handleSessionRecovery（副作用）', () => {
  it('token 过期 → 清 auth、跳登录（带 expired 标记）', () => {
    const onClear = vi.fn()
    const onRedirect = vi.fn()
    const d = handleSessionRecovery({
      getToken: () => expiredToken(),
      getPathname: () => '/resume',
      getRedirectTarget: () => '/resume',
      onClear,
      onRedirect,
    })
    expect(d.action).toBe('redirect-login')
    expect(onClear).toHaveBeenCalledTimes(1)
    expect(onRedirect).toHaveBeenCalledWith(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=${encodeURIComponent('/resume')}`)
  })

  it('token 有效 → 不产生任何副作用', () => {
    const onClear = vi.fn()
    const onRedirect = vi.fn()
    const d = handleSessionRecovery({
      getToken: () => validToken(),
      getPathname: () => '/resume',
      getRedirectTarget: () => '/resume',
      onClear,
      onRedirect,
    })
    expect(d.action).toBe('none')
    expect(onClear).not.toHaveBeenCalled()
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('已在登录页且 token 过期 → 不跳转、不清 auth', () => {
    const onClear = vi.fn()
    const onRedirect = vi.fn()
    const d = handleSessionRecovery({
      getToken: () => expiredToken(),
      getPathname: () => LOGIN_PATH,
      getRedirectTarget: () => LOGIN_PATH,
      onClear,
      onRedirect,
    })
    expect(d.action).toBe('none')
    expect(onClear).not.toHaveBeenCalled()
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('公开页脏 token → 只清 auth，不跳转、不提示（P1-02）', () => {
    const onClear = vi.fn()
    const onRedirect = vi.fn()
    const onNotify = vi.fn()
    const d = handleSessionRecovery({
      getToken: () => expiredToken(),
      getPathname: () => '/',
      getRedirectTarget: () => '/',
      onClear,
      onRedirect,
      onNotify,
    })
    expect(d.action).toBe('clear-and-stay')
    expect(onClear).toHaveBeenCalledTimes(1)
    expect(onRedirect).not.toHaveBeenCalled()
    expect(onNotify).not.toHaveBeenCalled()
  })

  it('匿名访客在首页 → 不产生任何副作用', () => {
    const onClear = vi.fn()
    const onRedirect = vi.fn()
    const d = handleSessionRecovery({
      getToken: () => '',
      getPathname: () => '/',
      getRedirectTarget: () => '/',
      onClear,
      onRedirect,
    })
    expect(d.action).toBe('none')
    expect(onClear).not.toHaveBeenCalled()
    expect(onRedirect).not.toHaveBeenCalled()
  })

  it('默认实现真的会清掉 localStorage 中的过期 token', () => {
    localStorage.setItem('token', expiredToken())
    localStorage.setItem('username', 'alice')
    const onRedirect = vi.fn()
    handleSessionRecovery({
      getPathname: () => '/resume',
      getRedirectTarget: () => '/resume',
      onRedirect,
    })
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('username')).toBeNull()
    expect(onRedirect).toHaveBeenCalledTimes(1)
  })
})

describe('utils/sessionGuard · installSessionGuard（信号监听）', () => {
  /**
   * 用真实的 jsdom document/window 安装，通过改写 visibilityState 并派发
   * 真实事件来验证「页面恢复可见 / 窗口聚焦」这两个信号确实会触发校验。
   *
   * 为了不真的跳转，测试里把 window.location 换成可写对象。
   */
  const realLocation = window.location
  function mockLocation(pathname: string) {
    const loc = { href: '', pathname, search: '' }
    Object.defineProperty(window, 'location', { value: loc, writable: true, configurable: true })
    return loc
  }

  afterEach(() => {
    __resetSessionGuardForTest()
    Object.defineProperty(window, 'location', { value: realLocation, writable: true, configurable: true })
    localStorage.clear()
  })

  it('页面恢复可见（visible）+ token 已过期 → 清 token 并跳登录', () => {
    __resetSessionGuardForTest()
    localStorage.setItem('token', expiredToken())
    const loc = mockLocation('/resume')
    installSessionGuard()

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    expect(localStorage.getItem('token')).toBeNull()
    expect(loc.href).toContain(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=`)
  })

  it('页面仍隐藏（hidden）→ 不校验、不跳转', () => {
    __resetSessionGuardForTest()
    localStorage.setItem('token', expiredToken())
    const loc = mockLocation('/resume')
    installSessionGuard()

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    expect(localStorage.getItem('token')).not.toBeNull()
    expect(loc.href).toBe('')
  })

  it('窗口重新聚焦（focus）+ token 已过期 → 清 token 并跳登录', () => {
    __resetSessionGuardForTest()
    localStorage.setItem('token', expiredToken())
    const loc = mockLocation('/job')
    installSessionGuard()

    window.dispatchEvent(new Event('focus'))

    expect(localStorage.getItem('token')).toBeNull()
    expect(loc.href).toContain(`${LOGIN_PATH}?${SESSION_EXPIRED_FLAG}=1&redirect=`)
  })

  it('token 有效 → 恢复可见不跳转、不清 token', () => {
    __resetSessionGuardForTest()
    localStorage.setItem('token', validToken())
    const loc = mockLocation('/resume')
    installSessionGuard()

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    expect(localStorage.getItem('token')).not.toBeNull()
    expect(loc.href).toBe('')
  })

  it('已在登录页 → 恢复可见不跳转（防循环）', () => {
    __resetSessionGuardForTest()
    localStorage.setItem('token', expiredToken())
    const loc = mockLocation(LOGIN_PATH)
    installSessionGuard()

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    expect(loc.href).toBe('')
  })

  it('安装幂等：重复调用只注册一份监听器', () => {
    __resetSessionGuardForTest()
    const docSpy = vi.spyOn(document, 'addEventListener')
    const winSpy = vi.spyOn(window, 'addEventListener')
    installSessionGuard()
    const docCallsAfterFirst = docSpy.mock.calls.filter((c) => c[0] === 'visibilitychange').length
    const winCallsAfterFirst = winSpy.mock.calls.filter((c) => c[0] === 'focus').length
    installSessionGuard() // 第二次应被 installed 标记挡住
    const docCallsAfterSecond = docSpy.mock.calls.filter((c) => c[0] === 'visibilitychange').length
    const winCallsAfterSecond = winSpy.mock.calls.filter((c) => c[0] === 'focus').length

    expect(docCallsAfterFirst).toBe(1)
    expect(winCallsAfterFirst).toBe(1)
    expect(docCallsAfterSecond).toBe(1)
    expect(winCallsAfterSecond).toBe(1)
    docSpy.mockRestore()
    winSpy.mockRestore()
  })
})

describe('utils/sessionGuard · 与 auth 状态联动', () => {
  beforeEach(() => {
    localStorage.clear()
    clearAuth()
  })

  it('过期 token 被清理后 authState 同步归零', () => {
    localStorage.setItem('token', expiredToken())
    setAuth(validToken(), 'bob') // 先建立合法会话
    // 手动把 localStorage 里的 token 换成过期的，模拟「24h 后过期」
    localStorage.setItem('token', expiredToken())
    // authState.token 仍是旧的合法值，此处用真实 handleSessionRecovery 走默认 getToken（读 localStorage）
    const onRedirect = vi.fn()
    const d = handleSessionRecovery({
      getPathname: () => '/profile',
      getRedirectTarget: () => '/profile',
      onRedirect,
    })
    expect(d.action).toBe('redirect-login')
    expect(authState.token).toBe('')
    expect(localStorage.getItem('token')).toBeNull()
  })
})

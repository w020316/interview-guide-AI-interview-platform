import { describe, it, expect } from 'vitest'
import {
  LOGIN_PATH,
  SESSION_EXPIRED_FLAG,
  SESSION_EXPIRED_MESSAGE,
  buildSessionExpiredLoginUrl,
} from './sessionExpiry'

/**
 * 用例来源：线上「登录后不关页面，隔一段时间回到该页面 → 所有功能都用不了」。
 *
 * 该现象此前有两条无声路径：`api/index.ts` 的请求拦截器（本地 token 失效）
 * 与响应拦截器（401）跳登录时**都不带 `expired=1`**，用户只看到
 * 「莫名其妙回到登录页」，无从判断是被登出还是系统坏了。
 * 本模块把「会话失效 → 跳登录」收敛成唯一约定，供 sessionGuard 与 api 共用。
 */

describe('utils/sessionExpiry · 常量', () => {
  it('登录页路径与标记值与既有约定一致（改动会破坏登录页提示的读取）', () => {
    expect(LOGIN_PATH).toBe('/login')
    expect(SESSION_EXPIRED_FLAG).toBe('expired')
  })

  it('提示文案是用户可读的，不含技术术语', () => {
    expect(SESSION_EXPIRED_MESSAGE).toBe('登录已过期，请重新登录')
    for (const bad of ['token', 'Token', '401', 'JWT', 'API']) {
      expect(SESSION_EXPIRED_MESSAGE.includes(bad)).toBe(false)
    }
  })
})

describe('utils/sessionExpiry · buildSessionExpiredLoginUrl', () => {
  it('带 redirect 时生成 /login?expired=1&redirect=...', () => {
    expect(buildSessionExpiredLoginUrl('/resume')).toBe('/login?expired=1&redirect=%2Fresume')
  })

  it('redirect 含查询串时整体编码（登录后能回到原页面原筛选）', () => {
    const url = buildSessionExpiredLoginUrl('/jobs?type=campus&page=2')
    expect(url.startsWith('/login?expired=1&redirect=')).toBe(true)
    // 查询串必须被编码进 redirect，否则会被登录页当成自己的参数
    expect(url).toContain(encodeURIComponent('/jobs?type=campus&page=2'))
    expect(url.indexOf('redirect=')).toBeGreaterThan(url.indexOf('expired=1'))
  })

  it('省略 redirect 时只带标记（登录后回首页）', () => {
    expect(buildSessionExpiredLoginUrl()).toBe('/login?expired=1')
  })

  it('redirect 为空串时只带标记（与省略等价，不产生空参数）', () => {
    expect(buildSessionExpiredLoginUrl('')).toBe('/login?expired=1')
  })

  it('始终携带 expired=1 —— 这是「为什么回到登录页」的唯一解释来源', () => {
    for (const input of ['/a', '/b?x=1', '', undefined]) {
      expect(buildSessionExpiredLoginUrl(input)).toContain('expired=1')
    }
  })

  it('生成的地址可被登录页按同名参数读回（自洽性）', () => {
    const url = buildSessionExpiredLoginUrl('/knowledge')
    const query = url.slice(url.indexOf('?') + 1)
    const params = new URLSearchParams(query)
    expect(params.get(SESSION_EXPIRED_FLAG)).toBe('1')
    expect(params.get('redirect')).toBe('/knowledge')
  })
})

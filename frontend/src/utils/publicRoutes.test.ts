import { describe, expect, it } from 'vitest'
import { isProtectedRoute, isPublicRoute } from './publicRoutes'

describe('publicRoutes（P1-02 路由公开性判定）', () => {
  it('首页 / 登录页 / 404 未匹配路径都属于公开侧', () => {
    expect(isPublicRoute('/')).toBe(true)
    expect(isPublicRoute('/login')).toBe(true)
    // 404：router 的 :pathMatch(.*)* 兜底，路径本身不在业务清单里
    expect(isPublicRoute('/no-such-page')).toBe(true)
    expect(isPublicRoute('/foo/bar/baz')).toBe(true)
  })

  it('业务页面需要登录（含子路径与末尾斜杠）', () => {
    for (const p of ['/resume', '/resume/history', '/resume/studio', '/job', '/jobs',
      '/agent', '/career', '/salary', '/applications', '/interview', '/history',
      '/learning', '/calendar', '/wrong-book', '/favorites', '/progress', '/profile',
      '/knowledge', '/admin']) {
      expect(isProtectedRoute(p)).toBe(true)
    }
    expect(isProtectedRoute('/resume/')).toBe(true)
  })

  /**
   * G-02 说明：详情页最初计划公开，但后端详情接口需要登录（匿名会 401）。
   * 前端单方面公开只会让访客看到报错页，因此 `/jobs/:id` 暂时仍算受保护。
   * 待后端 SecurityConfig 开放匿名读取后再改这里（届时本用例同步反转）。
   */
  it('岗位详情 /jobs/:id 当前仍需登录（与后端接口口径一致）', () => {
    expect(isProtectedRoute('/jobs/123')).toBe(true)
    expect(isProtectedRoute('/jobs')).toBe(true)
  })

  it('空路径按公开处理（浏览器尚未就绪时不应误跳登录）', () => {
    expect(isPublicRoute('')).toBe(true)
  })

  it('前缀不会误判（/job 不应命中 /jobs，反之亦然）', () => {
    expect(isProtectedRoute('/jobs')).toBe(true)
    expect(isProtectedRoute('/job')).toBe(true)
    expect(isPublicRoute('/jobless')).toBe(true)
  })
})

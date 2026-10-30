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

  it('岗位详情 /jobs/:id 公开（分享链接可直接打开，投递/收藏时再要求登录）', () => {
    expect(isPublicRoute('/jobs/123')).toBe(true)
    // 但列表页本身仍需登录
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

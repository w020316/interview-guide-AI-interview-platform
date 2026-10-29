import { describe, expect, it } from 'vitest'
import { safeRedirect } from './safeRedirect'

const ORIGIN = 'https://offergo.pages.dev'

describe('safeRedirect —— 只允许站内路径', () => {
  it('常规站内路径原样保留（含 query 与 hash）', () => {
    expect(safeRedirect('/jobs', ORIGIN)).toBe('/jobs')
    expect(safeRedirect('/jobs?tab=1', ORIGIN)).toBe('/jobs?tab=1')
    expect(safeRedirect('/jobs?tab=1#top', ORIGIN)).toBe('/jobs?tab=1#top')
    expect(safeRedirect('/', ORIGIN)).toBe('/')
  })

  it('协议相对地址 //evil.com 回退首页', () => {
    expect(safeRedirect('//evil.com', ORIGIN)).toBe('/')
    expect(safeRedirect('//evil.com/path', ORIGIN)).toBe('/')
  })

  it('反斜杠变体 /\\evil.com 回退首页（浏览器把 \\ 当 /）', () => {
    expect(safeRedirect('/\\evil.com', ORIGIN)).toBe('/')
    expect(safeRedirect('/\\/evil.com', ORIGIN)).toBe('/')
  })

  it('绝对外链回退首页', () => {
    expect(safeRedirect('https://evil.com', ORIGIN)).toBe('/')
    expect(safeRedirect('http://evil.com/x', ORIGIN)).toBe('/')
  })

  it('危险协议回退首页', () => {
    expect(safeRedirect('javascript:alert(1)', ORIGIN)).toBe('/')
    expect(safeRedirect('data:text/html,<script>alert(1)</script>', ORIGIN)).toBe('/')
  })

  it('空值与非字符串回退首页', () => {
    expect(safeRedirect(undefined, ORIGIN)).toBe('/')
    expect(safeRedirect(null, ORIGIN)).toBe('/')
    expect(safeRedirect('', ORIGIN)).toBe('/')
    expect(safeRedirect(123, ORIGIN)).toBe('/')
  })

  it('数组取第一项（vue-router 的 query 可能是 string[]）', () => {
    expect(safeRedirect(['/jobs', '/other'], ORIGIN)).toBe('/jobs')
    expect(safeRedirect(['//evil.com'], ORIGIN)).toBe('/')
  })
})

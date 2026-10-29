import { describe, expect, it } from 'vitest'
import { createLatestGuard } from './latestRequest'

describe('latestRequest · 最后一次请求为准', () => {
  it('同一张票据在无新请求时始终有效', () => {
    const guard = createLatestGuard()
    const ticket = guard.begin()
    expect(guard.isLatest(ticket)).toBe(true)
    expect(guard.isLatest(ticket)).toBe(true)
  })

  it('新请求发起后，旧票据立即失效（旧响应被丢弃）', () => {
    const guard = createLatestGuard()
    const first = guard.begin()
    const second = guard.begin()

    expect(guard.isLatest(first)).toBe(false)
    expect(guard.isLatest(second)).toBe(true)
  })

  it('票据单调递增且互不相等', () => {
    const guard = createLatestGuard()
    const tickets = [guard.begin(), guard.begin(), guard.begin()]
    expect(tickets).toEqual([1, 2, 3])
    expect(new Set(tickets).size).toBe(3)
  })

  it('两个视图各自持有一个守卫时互不干扰', () => {
    const a = createLatestGuard()
    const b = createLatestGuard()
    const ta = a.begin()
    b.begin()

    expect(a.isLatest(ta)).toBe(true)
  })
})

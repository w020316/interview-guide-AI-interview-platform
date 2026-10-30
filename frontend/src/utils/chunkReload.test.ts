import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearChunkReloadFlag, shouldAutoReloadForChunkError } from './chunkReload'

describe('chunkReload（P2-18 分块加载失败的一次性刷新）', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('首次失败允许自动刷新一次', () => {
    expect(shouldAutoReloadForChunkError()).toBe(true)
  })

  it('同一会话第二次失败不再自动刷新（避免无限刷新循环）', () => {
    expect(shouldAutoReloadForChunkError()).toBe(true)
    expect(shouldAutoReloadForChunkError()).toBe(false)
  })

  it('成功导航后清除标记，下一次抖动仍可自动恢复一次', () => {
    expect(shouldAutoReloadForChunkError()).toBe(true)
    clearChunkReloadFlag()
    expect(shouldAutoReloadForChunkError()).toBe(true)
  })

  it('sessionStorage 不可用时不自动刷新（宁可提示，也不能进循环）', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    try {
      expect(shouldAutoReloadForChunkError()).toBe(false)
    } finally {
      spy.mockRestore()
    }
  })
})

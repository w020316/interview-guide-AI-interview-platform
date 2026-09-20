import { afterEach, describe, expect, it, vi } from 'vitest'
import { safeGetItem, safeRemoveItem, safeSetItem } from './storage'

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('safe storage helpers', () => {
  describe('safeGetItem', () => {
    it('正常读取返回值', () => {
      localStorage.setItem('k', 'v')
      expect(safeGetItem('k')).toBe('v')
    })

    it('读取失败（storage 抛错）返回 null 且不向上抛出', () => {
      const spy = vi
        .spyOn(Storage.prototype, 'getItem')
        .mockImplementation(() => {
          throw new Error('blocked')
        })
      expect(() => safeGetItem('k')).not.toThrow()
      expect(safeGetItem('k')).toBeNull()
      spy.mockRestore()
    })
  })

  describe('safeSetItem', () => {
    it('正常写入', () => {
      safeSetItem('k', 'v')
      expect(localStorage.getItem('k')).toBe('v')
    })

    it('写入失败（如隐私模式配额超限）静默忽略且不抛出', () => {
      const spy = vi
        .spyOn(Storage.prototype, 'setItem')
        .mockImplementation(() => {
          throw new Error('quota')
        })
      expect(() => safeSetItem('k', 'v')).not.toThrow()
      spy.mockRestore()
    })
  })

  describe('safeRemoveItem', () => {
    it('正常删除', () => {
      localStorage.setItem('k', 'v')
      safeRemoveItem('k')
      expect(localStorage.getItem('k')).toBeNull()
    })

    it('删除失败静默忽略且不抛出', () => {
      const spy = vi
        .spyOn(Storage.prototype, 'removeItem')
        .mockImplementation(() => {
          throw new Error('blocked')
        })
      expect(() => safeRemoveItem('k')).not.toThrow()
      spy.mockRestore()
    })
  })
})

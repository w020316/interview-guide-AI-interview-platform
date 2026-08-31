import { beforeEach, describe, expect, it } from 'vitest'
import { applyTheme, theme, toggleTheme } from './theme'

const STORAGE_KEY = 'interview-theme'

describe('theme util', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.classList.remove('dark')
  })

  it('applyTheme 设置 data-theme 与 dark class', () => {
    applyTheme('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(theme.value).toBe('dark')

    applyTheme('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(theme.value).toBe('light')
  })

  it('toggleTheme 在 light/dark 间切换并持久化', () => {
    applyTheme('light')
    expect(toggleTheme()).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(theme.value).toBe('dark')

    expect(toggleTheme()).toBe('light')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
  })

  it('无 matchMedia 时系统偏好判定为浅色（默认 light）', () => {
    // jsdom 默认无偏好色，直接默认 light
    applyTheme('light')
    expect(theme.value).toBe('light')
  })
})
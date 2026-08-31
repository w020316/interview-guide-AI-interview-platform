import { ref } from 'vue'

/**
 * 主题切换工具
 * - 支持 'light' / 'dark' 两种主题
 * - 优先读取 localStorage 用户选择，其次跟随系统 prefers-color-scheme
 * - 通过给 <html> 设置 data-theme + element-plus 的 dark class 生效（与 design-system v4 变量联动）
 */

export type ThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'interview-theme'
const SYSTEM_QUERY = '(prefers-color-scheme: dark)'

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.(SYSTEM_QUERY).matches
}

/** 读取初始主题：localStorage > 系统偏好 > 默认 light */
function initialTheme(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'dark' || saved === 'light') return saved
  return systemPrefersDark() ? 'dark' : 'light'
}

const theme = ref<ThemeMode>(initialTheme())

/** 应用到 <html>：同时设 data-theme（本项目变量）与 dark class（Element Plus 暗色） */
export function applyTheme(mode: ThemeMode): void {
  theme.value = mode
  if (typeof document === 'undefined') return
  const el = document.documentElement
  el.setAttribute('data-theme', mode)
  el.classList.toggle('dark', mode === 'dark')
}

/** 切换主题并持久化到 localStorage */
export function toggleTheme(): ThemeMode {
  const next = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem(STORAGE_KEY, next)
  applyTheme(next)
  return next
}

/** 监听系统主题变化（用户未手动指定时自动跟随） */
export function initTheme(): void {
  applyTheme(initialTheme())
  if (typeof window === 'undefined' || typeof window.matchMedia === 'undefined') return
  const mq = window.matchMedia(SYSTEM_QUERY)
  const onChange = () => {
    // 仅当用户未手动选择过时才跟随系统
    if (!localStorage.getItem(STORAGE_KEY)) {
      applyTheme(systemPrefersDark() ? 'dark' : 'light')
    }
  }
  mq.addEventListener('change', onChange)
}

export { theme }
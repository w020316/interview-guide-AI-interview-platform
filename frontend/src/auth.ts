import { reactive } from 'vue'

/**
 * 全局认证状态（响应式）
 * - 解决 localStorage 非响应式导致的导航栏不更新问题
 * - 登录/退出时同步更新 localStorage 和响应式状态
 */
export const authState = reactive({
  token: localStorage.getItem('token') || '',
  username: localStorage.getItem('username') || '',
})

export function setAuth(token: string, username?: string) {
  authState.token = token
  localStorage.setItem('token', token)
  if (username) {
    authState.username = username
    localStorage.setItem('username', username)
  }
}

export function clearAuth() {
  authState.token = ''
  authState.username = ''
  localStorage.removeItem('token')
  localStorage.removeItem('username')
}

export function isLoggedIn(): boolean {
  return !!authState.token
}

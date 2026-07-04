import { reactive } from 'vue'

/**
 * 全局认证状态（响应式）
 * - 解决 localStorage 非响应式导致的导航栏不更新问题
 * - 登录/退出时同步更新 localStorage 和响应式状态
 * - 启动时校验 token 格式，防止 localStorage 被污染（如误存 AI API Key）
 */
function isValidJwt(token: string): boolean {
  // JWT 格式：header.payload.signature（三段式，用 . 分隔）
  // 若 token 不是该格式（如被误存为 sk-xxx 的 API Key），视为无效
  return typeof token === 'string' && token.split('.').length === 3 && token.length > 20
}

function loadValidToken(): string {
  const t = localStorage.getItem('token') || ''
  return isValidJwt(t) ? t : ''
}

function loadUsername(): string {
  return localStorage.getItem('username') || ''
}

// 启动时清理被污染的 token
if (localStorage.getItem('token') && !isValidJwt(localStorage.getItem('token') || '')) {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
}

export const authState = reactive({
  token: loadValidToken(),
  username: loadUsername(),
})

export function setAuth(token: string, username?: string) {
  if (!isValidJwt(token)) {
    // 拒绝非 JWT 格式的 token，避免污染状态
    console.warn('[auth] 拒绝非 JWT 格式的 token，已忽略')
    return
  }
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
  return isValidJwt(authState.token)
}

export { isValidJwt }

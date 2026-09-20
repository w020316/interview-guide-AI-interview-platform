import { reactive } from 'vue'
import { safeGetItem, safeRemoveItem, safeSetItem } from './utils/storage'

/**
 * 全局认证状态（响应式）
 * - 解决 localStorage 非响应式导致的导航栏不更新问题
 * - 登录/退出时同步更新 localStorage 和响应式状态
 * - 启动时校验 token 格式与过期时间，防止 localStorage 被污染（如误存 AI API Key）
 */

/** JWT 格式校验：必须为三段式 header.payload.signature */
function isValidJwt(token: string): boolean {
  return typeof token === 'string' && token.split('.').length === 3 && token.length > 20
}

/** 解析 JWT payload，失败返回 null */
function parseJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split('.')[1]
    // 浏览器 atob 支持 base64url 需补齐 padding
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized + '==='.slice((normalized.length + 3) % 4)
    return JSON.parse(atob(padded))
  } catch {
    return null
  }
}

/** 校验 token 是否已过期。P2-23：无 exp 字段视为无效（不再永久有效），过期返回 true */
function isTokenExpired(token: string): boolean {
  const payload = parseJwtPayload(token)
  if (!payload) return true
  const exp = payload.exp
  if (typeof exp !== 'number') return true // 无 exp 字段视为无效，避免伪造 token 永久可用
  return Date.now() / 1000 >= exp
}

/** 综合校验：格式合法且未过期 */
function isTokenValid(token: string): boolean {
  return isValidJwt(token) && !isTokenExpired(token)
}

function loadValidToken(): string {
  const t = safeGetItem('token') || ''
  return isTokenValid(t) ? t : ''
}

function loadUsername(): string {
  return safeGetItem('username') || ''
}

// 启动时清理被污染或已过期的 token
const _startupToken = safeGetItem('token') || ''
if (_startupToken && !isTokenValid(_startupToken)) {
  safeRemoveItem('token')
  safeRemoveItem('username')
}

export const authState = reactive({
  token: loadValidToken(),
  username: loadUsername(),
})

export function setAuth(token: string, username?: string) {
  if (!isValidJwt(token)) {
    // 拒绝非 JWT 格式的 token，避免污染状态
    if (!import.meta.env.PROD) {
      console.warn('[auth] 拒绝非 JWT 格式的 token，已忽略')
    }
    return
  }
  authState.token = token
  safeSetItem('token', token)
  if (username) {
    authState.username = username
    safeSetItem('username', username)
  }
}

export function clearAuth() {
  authState.token = ''
  authState.username = ''
  safeRemoveItem('token')
  safeRemoveItem('username')
}

/** 是否已登录（格式合法 + 未过期） */
export function isLoggedIn(): boolean {
  return isTokenValid(authState.token)
}

/** 当前登录用户是否为管理员（解析 JWT role claim；旧 token 无 claim 视为普通用户）v1.31.4 */
export function isAdmin(): boolean {
  if (!isTokenValid(authState.token)) return false
  const payload = parseJwtPayload(authState.token)
  return payload?.role === 'ROLE_ADMIN'
}

export { isValidJwt, parseJwtPayload, isTokenExpired, isTokenValid }


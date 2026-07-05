import axios from 'axios'
import { clearAuth, isTokenValid } from '../auth'

/**
 * 后端 API 统一封装
 * - 鉴权基于 Authorization: Bearer 头，无 CSRF 风险
 * - 若未来切换到 Cookie 方案，需引入 CSRF Token
 */

/**
 * 后端 API 基地址
 * - 生产环境：通过 Vercel rewrites 代理到 Render 后端，baseURL 留空（同源 /api）
 * - 显式配置 VITE_API_BASE_URL 时优先使用（直连后端，需 CORS）
 * - SSE fetch 必须使用此变量，避免与 axios baseURL 不一致
 */
export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || ''

const api = axios.create({
  // baseURL 末尾不带 /api，请求时统一以 /api/xxx 开头
  // 这样同源场景下走 Vercel rewrites，跨域场景下直连后端
  baseURL: apiBaseUrl || '/',
  timeout: 60000,
})

// AI 相关接口需要更长超时（冷启动 + AI 推理 30-60s）
export const AI_TIMEOUT = 120000

/**
 * 认证接口超时：Render 免费层冷启动可能需要 30-60s，给 90s 兜底
 */
export const AUTH_TIMEOUT = 90000

/**
 * 判断是否为 AI 接口（用于区分超时错误信息）
 */
function isAiRequest(url: string | undefined): boolean {
  if (!url) return false
  return url.includes('/resume/analyze') ||
         url.includes('/resume/upload') ||
         url.includes('/interview/generate') ||
         url.includes('/interview/evaluate') ||
         url.includes('/interview/stream') ||
         url.includes('/knowledge/ask')
}

/**
 * 判断是否为认证接口（用于区分超时错误信息）
 */
function isAuthRequest(url: string | undefined): boolean {
  if (!url) return false
  return url.includes('/auth/login') || url.includes('/auth/register')
}

/**
 * 统一错误信息提取，避免在各视图中重复 try/catch 模板
 * 用法：catch (e) { ElMessage.error(getErrMessage(e, '操作失败')) }
 *
 * 优先级：
 * 1. HTTP 错误响应中的后端友好 message（response.data.message）
 * 2. axios 拦截器已 reject 的 Error.message（业务错误、超时、网络错误）
 * 3. fallback 兜底
 */
export function getErrMessage(e: unknown, fallback: string): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  // 优先返回后端友好 message（HTTP 5xx 场景）
  const backendMsg = err?.response?.data?.message
  if (backendMsg) return backendMsg
  // 其次返回拦截器已处理过的 message（超时、网络错误、业务错误）
  if (e instanceof Error && e.message) return e.message
  // 最后兜底
  return err?.message || fallback
}

/**
 * 自动重试（仅对 GET 请求和网络错误重试，POST 不重试避免重复写入）
 */
async function retryRequest(config: any, retryCount = 1): Promise<any> {
  try {
    return await api.request(config)
  } catch (err: any) {
    const isNetworkError = err.message === 'Network Error' || err.code === 'ECONNABORTED'
    const canRetry = retryCount > 0 && isNetworkError && (config.method || 'get').toLowerCase() === 'get'
    if (canRetry) {
      await new Promise(r => setTimeout(r, 1500)) // 1.5s 后重试
      return retryRequest(config, retryCount - 1)
    }
    throw err
  }
}

// 请求拦截器：自动注入 JWT token + Content-Type
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  // 仅在 token 格式合法且未过期时注入，避免污染请求头
  if (token && isTokenValid(token)) {
    config.headers.Authorization = `Bearer ${token}`
  } else if (token && !isTokenValid(token)) {
    // token 非法或已过期，清除并跳登录
    clearAuth()
    if (window.location.pathname !== '/login') {
      window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname)
    }
  }
  // FormData 时让浏览器自动设置 Content-Type（multipart/form-data + boundary）
  if (config.data instanceof FormData) {
    delete config.headers['Content-Type']
  } else {
    config.headers['Content-Type'] = 'application/json'
  }
  // 认证接口使用更长超时（Render 冷启动兜底）
  if (isAuthRequest(config.url)) {
    config.timeout = AUTH_TIMEOUT
  }
  return config
})

// 响应拦截器：统一处理返回结构和 401 跳登录
api.interceptors.response.use(
  (response) => {
    const data = response.data
    // 防御：如果返回的是 HTML（Vercel SPA fallback），说明 API 代理未生效
    // 注意：data 可能为 null/undefined，需用安全转换
    const dataStr = data == null ? '' : String(data)
    if (typeof data === 'string' && data.trim().startsWith('<!DOCTYPE') || dataStr.includes('<html')) {
      return Promise.reject(new Error('API 不可达：收到 HTML 响应，请检查 vercel.json 反向代理配置或后端部署状态'))
    }
    // 后端 Result<T> 结构：{code, message, data}
    if (data && typeof data.code !== 'undefined') {
      if (data.code === 200 || data.code === 0) {
        return data.data
      }
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data
  },
  (error) => {
    // 401 和 403 都视为认证失效（Spring Security 未配置 AuthenticationEntryPoint 时默认返回 403）
    if (error.response?.status === 401 || error.response?.status === 403) {
      // 登录页不跳转（避免循环），其他页面清除 auth 并跳登录
      if (window.location.pathname !== '/login') {
        clearAuth()
        window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname)
      }
    }
    // 超时单独提示，根据接口类型区分错误信息
    if (error.code === 'ECONNABORTED') {
      const url = error.config?.url || ''
      if (isAiRequest(url)) {
        error.message = 'AI 服务响应超时，可能正在冷启动或推理中，请稍后重试'
      } else if (isAuthRequest(url)) {
        error.message = '后端服务正在冷启动（首次访问需 30-60s 唤醒），请等待几秒后重试'
      } else {
        error.message = '请求超时，后端服务可能正在冷启动，请稍后重试'
      }
    }
    // 检测 HTML 响应（API 代理失效，Vercel 返回 index.html）
    const contentType = error.response?.headers?.['content-type'] || ''
    const respData = error.response?.data
    if (contentType.includes('text/html') || (typeof respData === 'string' && respData.includes('<html'))) {
      error.message = '后端服务未响应，Render 免费层可能正在冷启动，请等待 30-60s 后重试'
    }
    // 网络层错误（DNS/连接失败）
    if (error.message === 'Network Error') {
      error.message = '网络连接失败，请检查网络后重试（后端服务可能正在冷启动）'
    }
    return Promise.reject(error)
  }
)

export default api

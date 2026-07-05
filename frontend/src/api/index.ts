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
    if (error.response?.status === 401) {
      clearAuth()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname)
      }
    }
    // 超时单独提示，便于用户排查
    if (error.code === 'ECONNABORTED') {
      error.message = '请求超时，AI 服务可能正在冷启动，请稍后重试'
    }
    // 检测 HTML 响应（API 代理失效，Vercel 返回 index.html）
    const contentType = error.response?.headers?.['content-type'] || ''
    const respData = error.response?.data
    if (contentType.includes('text/html') || (typeof respData === 'string' && respData.includes('<html'))) {
      error.message = 'API 不可达：后端服务未响应，请检查部署配置或稍后重试（Render 免费层可能正在冷启动）'
    }
    // 网络层错误（DNS/连接失败）
    if (error.message === 'Network Error') {
      error.message = '网络连接失败，请检查网络或后端服务是否可用'
    }
    return Promise.reject(error)
  }
)

export default api

import axios from 'axios'
import { clearAuth, isValidJwt } from '../auth'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000,
})

// AI 相关接口需要更长超时（冷启动 + AI 推理 30-60s）
export const AI_TIMEOUT = 120000

// 请求拦截器：自动注入 JWT token + Content-Type
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  // 仅在 token 为合法 JWT 格式时注入，避免污染请求头（如 sk-xxx API Key）
  if (token && isValidJwt(token)) {
    config.headers.Authorization = `Bearer ${token}`
  } else if (token && !isValidJwt(token)) {
    // token 格式非法，清除污染数据并跳转登录
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

// 响应拦截器：统一处理返回结构与 401 跳登录
api.interceptors.response.use(
  (response) => {
    const data = response.data
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
    return Promise.reject(error)
  }
)

export default api

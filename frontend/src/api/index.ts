import axios, { type InternalAxiosRequestConfig } from 'axios'
import { clearAuth, isTokenValid } from '../auth'
import { apiBaseUrl } from './baseUrl'
import { ensureAwake } from '../utils/backendWake'

/**
 * 后端 API 统一封装
 * - 鉴权基于 Authorization: Bearer 头，无 CSRF 风险
 * - 若未来切换到 Cookie 方案，需引入 CSRF Token
 * - 冷启动韧性：见 utils/backendWake.ts（实测 Render 免费层冷启动约 98s）
 */

export { apiBaseUrl }

/** 普通请求超时 */
export const DEFAULT_TIMEOUT = 90000

/**
 * AI 相关接口超时：冷启动 + AI 推理（UI 宣称出题通常需要 2-3 分钟）
 */
export const AI_TIMEOUT = 180000

/**
 * 认证接口超时。
 *
 * 2026-09-19 修正：实测线上（Render 免费层）冷启动 /actuator/health 耗时 **98.1s**，
 * 原值 90s 必然先于后端就绪而超时，导致「首次登录必失败」。
 * 提升至 150s 覆盖最慢的合法冷启动；配合 backendWake 的主动预热，正常情况下不会等到这个上限。
 */
export const AUTH_TIMEOUT = 150000

const api = axios.create({
  // baseURL 末尾不带 /api，请求时统一以 /api/xxx 开头
  // 这样同源场景下走反向代理，跨域场景下直连后端
  baseURL: apiBaseUrl || '/',
  timeout: DEFAULT_TIMEOUT,
})

/**
 * 长耗时 AI 接口路径特征。
 *
 * 用途：命中「AI 服务响应超时」专用文案（而非通用网络错误提示）。
 * 注意：**是否允许冷启动重放不再依赖本清单**——重放仅对幂等 GET 生效，
 * 从结构上杜绝 AI 生成类 POST 被重放导致的双份推理/重复入库。
 */
const AI_PATH_FRAGMENTS: readonly string[] = [
  '/api/resume/analyze',
  '/api/resume/upload',
  '/api/resume/optimize',
  '/api/resume/import-url',
  '/api/interview/questions',
  '/api/interview/evaluate',
  '/api/interview/followup',
  '/api/interview/upload-image',
  '/api/interview/ask/stream',
  '/api/session/create',
  '/api/session/answer',
  '/api/knowledge/ask',
  '/api/knowledge/import',
  '/api/job/analyze',
  '/api/job/gap',
  '/api/job/letter',
  '/api/jobs/refresh',
  '/api/favorite/bank/start',
  '/api/agent/',
  // v1.35.0：求职 Skill 与投递台账（定制简历为 AI 生成）
  '/api/career/',
  '/api/application/',
  // v1.36.0：面试故事库（提炼/质检/追问均为 AI 调用）
  '/api/story-bank/',
]

/**
 * 判断是否为 AI 接口。
 *
 * 双通道判定，避免清单随接口演进而失效：
 * 1. 路径命中 {@link AI_PATH_FRAGMENTS}
 * 2. 调用方显式传入 AI_TIMEOUT（各视图的 AI 调用统一写法，等于自带标注）
 */
function isAiRequest(url: string | undefined, timeout?: number): boolean {
  if (timeout === AI_TIMEOUT) return true
  if (!url) return false
  return AI_PATH_FRAGMENTS.some((frag) => url.includes(frag))
}

/**
 * 判断是否为认证接口（用于区分超时错误信息）
 */
export function isAuthRequest(url: string | undefined): boolean {
  if (!url) return false
  return url.includes('/auth/login') || url.includes('/auth/register')
}

/** 从请求配置中解析「是否 AI 请求」（供错误处理复用） */
function isAiConfig(cfg: (InternalAxiosRequestConfig & { url?: string }) | undefined): boolean {
  return isAiRequest(cfg?.url, cfg?.timeout)
}

/**
 * 判定错误是否属于「后端冷启动 / 未就绪」信号。
 *
 * 覆盖三类真实观测到的现象（Render 免费层）：
 * 1. 无响应体（连接被中断 / DNS 未就绪）→ 裸 Network Error
 * 2. 本地超时（后端仍在启动，连接被长时间挂起）→ ECONNABORTED
 * 3. 边缘节点返回 502/503/504 且响应体不是后端业务 JSON（后端进程尚未监听）
 *
 * 注意：后端业务 503（如「AI 服务暂时不可用」）走在 HTTP 200 + body.code 通道，
 * 在响应拦截器 fulfilled 分支就已被转成普通 Error，不会进入本判定，因此不会误判。
 */
export function isColdStartError(e: unknown): boolean {
  const err = e as {
    code?: string
    message?: string
    response?: { status?: number; data?: unknown; headers?: Record<string, string> }
  }
  if (!err) return false

  const status = err.response?.status
  if (status === 502 || status === 503 || status === 504) {
    const body = err.response?.data
    const isBusinessJson =
      body != null && typeof body === 'object' && typeof (body as { code?: unknown }).code !== 'undefined'
    if (!isBusinessJson) return true
  }

  const contentType = String(err.response?.headers?.['content-type'] || '')
  const body = err.response?.data
  if (contentType.includes('text/html') || (typeof body === 'string' && body.includes('<html'))) {
    return true
  }

  if (err.code === 'ECONNABORTED') return true
  if (err.message === 'Network Error') return true

  const msg = typeof err.message === 'string' ? err.message : ''
  return msg.includes('冷启动') || msg.includes('后端服务未响应') || msg.includes('网络连接失败')
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

// 请求拦截器：自动注入 JWT token + Content-Type + 超时
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  // 仅在 token 格式合法且未过期时注入，避免污染请求头
  if (token && isTokenValid(token)) {
    config.headers.Authorization = `Bearer ${token}`
  } else if (token && !isTokenValid(token)) {
    // token 非法或已过期，清除并跳登录
    clearAuth()
    if (window.location.pathname !== '/login') {
      window.location.href = '/login?redirect=' + encodeURIComponent(currentRelativeUrl())
    }
  }
  // FormData 时让浏览器自动设置 Content-Type（multipart/form-data + boundary）
  if (config.data instanceof FormData) {
    delete config.headers['Content-Type']
  } else {
    config.headers['Content-Type'] = 'application/json'
  }
  // AI 调用方未显式指定超时时，按 AI 接口兜底（避免用 90s 卡死长推理）
  if (config.timeout === undefined && isAiRequest(config.url)) {
    config.timeout = AI_TIMEOUT
  }
  // 认证接口使用更长超时（Render 冷启动兜底）
  if (isAuthRequest(config.url)) {
    config.timeout = AUTH_TIMEOUT
  }
  return config
})

/** 当前页面相对地址（含查询串），用于登录后回跳 */
function currentRelativeUrl(): string {
  return window.location.pathname + window.location.search
}

// 响应拦截器：统一处理返回结构和 401 跳登录
api.interceptors.response.use(
  (response) => {
    const data = response.data
    // 防御：如果返回的是 HTML（SPA fallback），说明 API 代理未生效
    // 注意：data 可能为 null/undefined，需用安全转换
    if (typeof data === 'string' && (data.trim().startsWith('<!DOCTYPE') || data.includes('<html'))) {
      return Promise.reject(new Error('API 不可达：收到 HTML 响应，请检查反向代理配置或后端部署状态'))
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
  async (error) => {
    const cfg = error.config as (InternalAxiosRequestConfig & { url?: string; __retried?: boolean }) | undefined
    const url = cfg?.url || ''
    const method = String(cfg?.method || 'get').toLowerCase()

    // 401 和 403 都视为认证失效（Spring Security 未配置 AuthenticationEntryPoint 时默认返回 403）
    if (error.response?.status === 401 || error.response?.status === 403) {
      // 登录页不跳转（避免循环），其他页面清除 auth 并跳登录
      if (window.location.pathname !== '/login') {
        clearAuth()
        window.location.href = '/login?redirect=' + encodeURIComponent(currentRelativeUrl())
      }
      return Promise.reject(error)
    }

    const coldStart = isColdStartError(error)

    // 冷启动自动重放：**仅对「非 AI 的幂等 GET」生效**。
    // 设计取舍（2026-09-19 收紧）：
    // - 原实现按「非 AI 路径」放行重放，但 AI 路径清单无法穷举（/api/job/analyze、
    //   /api/resume/optimize、POST /api/session/{id}/questions、/api/interview/followup 等
    //   长期游离在清单之外），一旦命中会静默重放 → **双份 AI 推理 + 重复入库**；
    //   同理非 AI 的 POST（如收藏切换）重放也会造成重复写入。
    // - 改为「方法 + AI 标记」双重判定后：非幂等请求永不重放，AI 请求永不重放，
    //   风险从"清单维护是否跟得上"变为结构性保证。
    // - 登录/注册是 POST，由 LoginView 的显式唤醒 + 单次重试流程负责（见 authWithRetry）。
    if (
      coldStart &&
      cfg &&
      !cfg.__retried &&
      method === 'get' &&
      !isAiConfig(cfg) &&
      !url.includes('/api/info')
    ) {
      cfg.__retried = true
      try {
        const awake = await ensureAwake()
        if (awake) {
          return await api.request(cfg)
        }
      } catch {
        // 唤醒或重放失败，落到下方统一文案
      }
      const friendly = '后端服务唤醒超时，请稍后重试（免费实例冷启动约需 1-2 分钟）'
      const wrapped = Object.assign(new Error(friendly), { config: cfg, response: error.response })
      return Promise.reject(wrapped)
    }

    // 超时单独提示，根据接口类型区分错误信息
    if (error.code === 'ECONNABORTED') {
      if (isAiConfig(cfg)) {
        error.message = 'AI 服务响应超时，可能正在冷启动或推理中，请稍后重试'
      } else if (isAuthRequest(url)) {
        error.message = '后端服务正在冷启动（首次访问需 1-2 分钟唤醒），请稍等后重试'
      } else {
        error.message = '请求超时，后端服务可能正在冷启动，请稍后重试'
      }
    } else if (coldStart) {
      // 502/503/504 与 HTML 响应此前会原样抛出 "Request failed with status code 502"
      // 这类无法理解的英文错误（且不触发任何重试），现统一转为可读、可操作的文案
      if (isAiConfig(cfg)) {
        error.message = 'AI 服务暂时不可用（后端可能正在冷启动），请等待 30-60s 后重试'
      } else if (error.response) {
        error.message = '后端服务未响应，可能正在冷启动，请等待 30-60s 后重试'
      } else {
        error.message = '网络连接失败，请检查网络后重试（后端服务可能正在冷启动）'
      }
    }

    return Promise.reject(error)
  }
)

export default api

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * 屏蔽真实唤醒探测：backendWake 走裸 axios，不 mock 会在 jsdom 里发出真实 XHR
 * （用例变慢并刷出大量 AggregateError 噪声）。唤醒逻辑本身由
 * utils/backendWake.test.ts 专项覆盖，此处只关心拦截器如何消费它的返回值。
 *
 * 使用 vi.hoisted：vi.mock 的工厂会被提升到文件顶部执行，普通 const 此时尚未初始化。
 */
const { ensureAwakeMock } = vi.hoisted(() => ({
  ensureAwakeMock: vi.fn(() => Promise.resolve(false)),
}))
vi.mock('../utils/backendWake', () => ({
  ensureAwake: () => ensureAwakeMock(),
}))

import api, { getErrMessage, AUTH_TIMEOUT, AI_TIMEOUT, DEFAULT_TIMEOUT, apiBaseUrl, isColdStartError, isNetworkLayerFailure } from './index'

/** 构造一个未过期的合法 JWT（3 段式，payload 含未来 exp） */
function validToken(): string {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))
  return 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.' + payload + '.c2lnbmF0dXJldmFsdWUxMjM0NQ'
}

// 拦截器处理器（axios InterceptorManager 的 handlers 数组）
const reqFulfilled = ((api.interceptors.request as unknown as { handlers: Array<{ fulfilled: (c: any) => any }> }).handlers[0]).fulfilled
const resFulfilled = ((api.interceptors.response as unknown as { handlers: Array<{ fulfilled: (r: any) => any }> }).handlers[0]).fulfilled
const resRejected = ((api.interceptors.response as unknown as { handlers: Array<{ rejected: (e: any) => any }> }).handlers[0]).rejected

const realLocation = window.location
async function mockLocation(pathname: string) {
  const loc = { href: '', pathname, search: '' }
  Object.defineProperty(window, 'location', { value: loc, writable: true, configurable: true })
  return loc
}

describe('api/index 常量', () => {
  it('导出 base URL（无环境变量时为空串）', () => {
    expect(typeof apiBaseUrl).toBe('string')
  })
  it('AI 超时与认证超时已配置', () => {
    expect(AI_TIMEOUT).toBeGreaterThan(AUTH_TIMEOUT)
    expect(AUTH_TIMEOUT).toBe(150000)
  })
  it('认证超时必须大于实测冷启动耗时（98s），否则首次登录必然先超时', () => {
    expect(AUTH_TIMEOUT).toBeGreaterThan(98000)
  })
  it('普通请求超时就绪于冷启动（默认值不低于 60s）', () => {
    expect(DEFAULT_TIMEOUT).toBeGreaterThanOrEqual(60000)
  })
})

describe('api/index 请求拦截器（fulfilled）', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    Object.defineProperty(window, 'location', { value: realLocation, writable: true, configurable: true })
  })

  it('token 合法时注入 Authorization', async () => {
    localStorage.setItem('token', validToken())
    const cfg = { headers: {}, url: '/api/a' }
    const out = await reqFulfilled(cfg)
    expect(out.headers.Authorization).toBe(`Bearer ${validToken()}`)
  })

  it('无 token 时不注入 Authorization，但设置 JSON Content-Type', async () => {
    const cfg: any = { headers: {}, url: '/api/b' }
    const out = await reqFulfilled(cfg)
    expect(out.headers.Authorization).toBeUndefined()
    expect(out.headers['Content-Type']).toBe('application/json')
  })

  it('token 非法/过期时清除并跳转登录', async () => {
    localStorage.setItem('token', 'bad.token')
    await mockLocation('/resume')
    const cfg: any = { headers: {}, url: '/api/c' }
    const out = await reqFulfilled(cfg)
    expect(localStorage.getItem('token')).toBeNull()
    expect((window.location as any).href).toContain('/login?redirect=')
  })

  it('登录页遇到过期 token 不跳转（避免循环）', async () => {
    localStorage.setItem('token', 'bad.token')
    await mockLocation('/login')
    const cfg: any = { headers: {}, url: '/api/d' }
    await reqFulfilled(cfg)
    expect((window.location as any).href).toBe('')
  })

  it('FormData 时删除 Content-Type，由浏览器自动设置 boundary', async () => {
    localStorage.setItem('token', validToken())
    const fd = new FormData()
    const cfg: any = { headers: {}, url: '/api/upload', data: fd }
    const out = await reqFulfilled(cfg)
    expect(out.headers['Content-Type']).toBeUndefined()
  })

  it('认证接口使用更长超时', async () => {
    localStorage.setItem('token', validToken())
    const cfg: any = { headers: {}, url: '/api/auth/login' }
    const out = await reqFulfilled(cfg)
    expect(out.timeout).toBe(AUTH_TIMEOUT)
  })
})

describe('api/index 响应拦截器（fulfilled）', () => {
  it('code=200 时返回 data', () => {
    const res: any = { data: { code: 200, message: 'ok', data: { id: 1 } } }
    expect(resFulfilled(res)).toEqual({ id: 1 })
  })
  it('code=0 时返回 data', () => {
    const res: any = { data: { code: 0, data: [1, 2] } }
    expect(resFulfilled(res)).toEqual([1, 2])
  })
  it('code!=200/0 时 reject 并携带 message', async () => {
    const res: any = { data: { code: 500, message: '服务异常' } }
    await expect(resFulfilled(res)).rejects.toThrow('服务异常')
  })
  it('收到 HTML 响应时 reject（API 代理失效提示）', async () => {
    const res: any = { data: '<!DOCTYPE html><html><body>404</body></html>' }
    await expect(resFulfilled(res)).rejects.toThrow('API 不可达')
  })
  it('无 code 字段的普通数据原样返回', () => {
    const res: any = { data: { foo: 'bar' } }
    expect(resFulfilled(res)).toEqual({ foo: 'bar' })
  })
  it('data 为 null 时返回 null 而非报错', () => {
    const res: any = { data: null }
    expect(resFulfilled(res)).toBeNull()
  })
})

describe('api/index 响应拦截器（rejected）', () => {
  beforeEach(() => {
    ensureAwakeMock.mockReset()
    ensureAwakeMock.mockResolvedValue(false)
  })
  afterEach(() => {
    Object.defineProperty(window, 'location', { value: realLocation, writable: true, configurable: true })
    vi.restoreAllMocks()
  })

  it('401 时清除认证并跳转登录', async () => {
    localStorage.setItem('token', validToken())
    await mockLocation('/history')
    const err: any = { config: { url: '/api/h' }, response: { status: 401, data: {} } }
    await expect(resRejected(err)).rejects.toBe(err)
    expect(localStorage.getItem('token')).toBeNull()
    expect((window.location as any).href).toContain('/login?redirect=')
  })

  it('403 不再清 token（P2-05：授权失败 ≠ 认证失效，保留当前会话上下文）', async () => {
    localStorage.setItem('token', validToken())
    await mockLocation('/profile')
    const err: any = {
      config: { url: '/api/admin/users' },
      response: { status: 403, data: { code: 403, message: '无权访问该资源' } },
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: '无权访问该资源' })
    expect(localStorage.getItem('token')).not.toBeNull()
    expect((window.location as any).href).toBe('')
  })

  it('403 且响应体非业务 JSON（网关拦截页）时提示被安全策略拦截', async () => {
    localStorage.setItem('token', validToken())
    await mockLocation('/jobs')
    const err: any = {
      config: { url: '/api/jobs', method: 'get' },
      response: { status: 403, data: '<html><title>Blocked</title></html>', headers: {} },
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('安全策略拦截') })
    expect(localStorage.getItem('token')).not.toBeNull()
  })

  it('登录页遇到 401 不跳转', async () => {
    await mockLocation('/login')
    const err: any = { config: { url: '/api/login' }, response: { status: 401, data: {} } }
    await expect(resRejected(err)).rejects.toBe(err)
    expect((window.location as any).href).toBe('')
  })

  it('AI 接口超时给出特定提示', async () => {
    // /api/resume/optimize 是此前遗漏在清单之外的 AI 端点，挑选它作为回归样本
    const err: any = {
      code: 'ECONNABORTED',
      config: { url: '/api/resume/optimize', method: 'post' },
      message: '',
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: 'AI 服务响应超时，可能正在冷启动或推理中，请稍后重试' })
  })

  it('显式 AI_TIMEOUT 的请求同样按 AI 口径提示（不依赖路径清单）', async () => {
    const err: any = {
      code: 'ECONNABORTED',
      config: { url: '/api/whatever/new-ai-endpoint', timeout: AI_TIMEOUT },
      message: '',
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('AI 服务响应超时') })
  })

  it('认证接口超时给出冷启动提示', async () => {
    const err: any = { code: 'ECONNABORTED', config: { url: '/api/auth/login' }, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('冷启动') })
  })

  it('通用超时给出默认提示（非幂等请求不进入唤醒重放）', async () => {
    const err: any = { code: 'ECONNABORTED', config: { url: '/api/favorite/toggle', method: 'post' }, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求超时') })
  })

  it('HTML 内容响应提示后端未响应', async () => {
    const err: any = { config: { url: '/api/x', method: 'post' }, response: { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' }, data: '<html>404</html>' } }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('后端服务未响应') })
  })

  it('纯 Network Error 给出中性文案（不再误报后端冷启动，P2-04）', async () => {
    const err: any = { config: { url: '/api/y', method: 'post' }, message: 'Network Error' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求未收到响应') })
  })

  it('GET 请求 Network Error 不再触发唤醒重放（P2-04：无法区分断网 / 网关拦截 / 后端未就绪）', async () => {
    const cfg: any = { url: '/api/favorites/list', method: 'get', __retried: undefined }
    const err: any = { config: cfg, response: undefined, message: 'Network Error' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求未收到响应') })
    expect(cfg.__retried).toBeUndefined()
    expect(ensureAwakeMock).not.toHaveBeenCalled()
  })

  it('搜索被 WAF 拦截（无 CORS 头的 403 → 浏览器只报 Network Error）不重放、不误报冷启动（P2-04 回归）', async () => {
    // 复现线上形状：curl 同一 URL 得到 HTTP 403 + <title>Blocked</title>，
    // 但因响应缺 CORS 头，浏览器侧只剩 net::ERR_FAILED / Network Error。
    const cfg: any = { url: '/api/jobs', method: 'get', params: { keyword: "' OR 1=1" }, __retried: undefined }
    const err: any = { config: cfg, response: undefined, message: 'Network Error' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求未收到响应') })
    expect(cfg.__retried).toBeUndefined()
    expect(ensureAwakeMock).not.toHaveBeenCalled()
  })

  it('GET 请求本地超时（ECONNABORTED）同样尝试唤醒重放（幂等，可安全重放）', async () => {
    const cfg: any = { url: '/api/jobs/list', method: 'get', __retried: undefined }
    const err: any = { code: 'ECONNABORTED', config: cfg, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('唤醒超时') })
    expect(cfg.__retried).toBe(true)
  })

  it('后端唤醒成功后将原请求重放一次并返回结果（由明确的未就绪信号触发）', async () => {
    ensureAwakeMock.mockResolvedValue(true)
    const replay = vi.spyOn(api, 'request').mockResolvedValue('ok' as never)
    const cfg: any = { url: '/api/stats/dashboard', method: 'get', __retried: undefined }
    const err: any = { code: 'ECONNABORTED', config: cfg, message: '' }
    await expect(resRejected(err)).resolves.toBe('ok')
    expect(replay).toHaveBeenCalledTimes(1)
    expect(cfg.__retried).toBe(true)
  })

  it('已经重放过一次的请求不再重放，避免无限循环', async () => {
    const cfg: any = { url: '/api/favorites/list', method: 'get', __retried: true }
    const err: any = { code: 'ECONNABORTED', config: cfg, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求超时') })
    expect(ensureAwakeMock).not.toHaveBeenCalled()
  })

  it('AI 生成类 POST 即使命中冷启动信号也不自动重放（防双份推理与重复入库）', async () => {
    const cfg: any = { url: '/api/interview/questions', method: 'post', __retried: undefined }
    const err: any = {
      config: cfg,
      response: { status: 502, data: '<html>Bad Gateway</html>', headers: {} },
      message: 'Request failed with status code 502',
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('AI 服务暂时不可用') })
    expect(cfg.__retried).toBeUndefined()
    expect(ensureAwakeMock).not.toHaveBeenCalled()
  })

  it('非 AI 的 POST 同样不重放（防重复写入，如收藏切换/提交答案）', async () => {
    const cfg: any = { url: '/api/favorite/toggle', method: 'post', __retried: undefined }
    const err: any = {
      config: cfg,
      response: { status: 502, data: 'Bad Gateway', headers: {} },
      message: 'Request failed with status code 502',
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('后端服务未响应') })
    expect(cfg.__retried).toBeUndefined()
    expect(ensureAwakeMock).not.toHaveBeenCalled()
  })

  it('清单外的 AI POST（如 /api/job/analyze）也不重放', async () => {
    const cfg: any = { url: '/api/job/analyze', method: 'post', __retried: undefined }
    const err: any = {
      config: cfg,
      response: { status: 502, data: 'Bad Gateway', headers: {} },
      message: 'Request failed with status code 502',
    }
    await expect(resRejected(err)).rejects.toBeTruthy()
    expect(cfg.__retried).toBeUndefined()
  })

  it('AI 接口本地超时（ECONNABORTED）不重放，防双份生成', async () => {
    const cfg: any = { url: '/api/interview/questions', method: 'post', __retried: undefined }
    const err: any = { code: 'ECONNABORTED', config: cfg, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('AI 服务响应超时') })
    expect(cfg.__retried).toBeUndefined()
  })

  it('边缘节点 502（非业务 JSON）按冷启动处理并给出可读文案', async () => {
    const err: any = {
      config: { url: '/api/auth/login', method: 'post' },
      response: { status: 502, data: '<html>Bad Gateway</html>', headers: {} },
      message: 'Request failed with status code 502',
    }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('冷启动') })
  })

  it('普通错误保持原样 reject', async () => {
    const err = new Error('业务错误')
    await expect(resRejected(err)).rejects.toThrow('业务错误')
  })
})

describe('api/index isColdStartError', () => {
  it('无响应的 Network Error **不**判定为冷启动信号（P2-04 回归）', () => {
    // 原断言是 true —— 正是「把缺陷锁进测试」：`' OR 1=1` 被 Render 边缘 WAF 以 403 拦截
    // （HTML 页且不带 CORS 头）时，浏览器只能上报 Network Error，前端据此误判为冷启动，
    // 提示「后端服务唤醒超时（冷启动约需 1-2 分钟）」并静默重放一次。
    expect(isColdStartError({ message: 'Network Error' })).toBe(false)
  })
  it('ECONNABORTED 判定为冷启动信号', () => {
    expect(isColdStartError({ code: 'ECONNABORTED' })).toBe(true)
  })
  it('502 且响应体非业务 JSON 判定为冷启动信号', () => {
    expect(isColdStartError({ response: { status: 502, data: 'Bad Gateway' } })).toBe(true)
  })
  it('503 且响应体为后端 Result JSON 时不算冷启动（属业务降级，不应触发唤醒）', () => {
    expect(isColdStartError({ response: { status: 503, data: { code: 503, message: 'AI 服务暂时不可用' } } })).toBe(false)
  })
  it('普通 400 业务错误不算冷启动', () => {
    expect(isColdStartError({ response: { status: 400, data: { code: 400, message: '用户名已存在' } } })).toBe(false)
  })
})

describe('api/index isNetworkLayerFailure', () => {
  it('裸 Network Error（无任何 HTTP 响应）判定为网络层失败', () => {
    expect(isNetworkLayerFailure({ message: 'Network Error' })).toBe(true)
  })
  it('拿得到 HTTP 响应时不算网络层失败（交由 status 分支判定）', () => {
    expect(isNetworkLayerFailure({ response: { status: 502 }, message: 'Bad Gateway' })).toBe(false)
  })
  it('本地超时不算网络层失败（属后端未就绪信号，会走冷启动文案）', () => {
    expect(isNetworkLayerFailure({ code: 'ECONNABORTED', message: 'timeout of 0ms exceeded' })).toBe(false)
  })
})

describe('api/index getErrMessage', () => {
  it('优先返回后端 response.data.message', () => {
    const err = {
      response: { data: { message: '后端业务错误' } },
      message: 'axios 错误',
    }
    expect(getErrMessage(err, '兜底')).toBe('后端业务错误')
  })

  it('无 response.data.message 时返回 Error.message', () => {
    const err = new Error('网络错误')
    expect(getErrMessage(err, '兜底')).toBe('网络错误')
  })

  it('无 response 和 message 时返回 fallback', () => {
    const err = { foo: 'bar' }
    expect(getErrMessage(err, '兜底文案')).toBe('兜底文案')
  })

  it('null 时返回 fallback', () => {
    expect(getErrMessage(null, '兜底')).toBe('兜底')
  })

  it('undefined 时返回 fallback', () => {
    expect(getErrMessage(undefined, '兜底')).toBe('兜底')
  })

  it('response.data.message 为空字符串时回退到 Error.message', () => {
    const err = {
      response: { data: { message: '' } },
      message: 'axios 错误',
    }
    // 空字符串是 falsy，应回退
    expect(getErrMessage(err, '兜底')).toBe('axios 错误')
  })
})

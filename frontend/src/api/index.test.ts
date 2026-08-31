import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import api, { getErrMessage, AUTH_TIMEOUT, AI_TIMEOUT, apiBaseUrl } from './index'

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
    expect(AUTH_TIMEOUT).toBe(90000)
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
  afterEach(() => {
    Object.defineProperty(window, 'location', { value: realLocation, writable: true, configurable: true })
  })

  it('401 时清除认证并跳转登录', async () => {
    localStorage.setItem('token', validToken())
    await mockLocation('/history')
    const err: any = { config: { url: '/api/h' }, response: { status: 401, data: {} } }
    await expect(resRejected(err)).rejects.toBe(err)
    expect(localStorage.getItem('token')).toBeNull()
    expect((window.location as any).href).toContain('/login?redirect=')
  })

  it('403 同样按认证失效处理', async () => {
    localStorage.setItem('token', validToken())
    await mockLocation('/profile')
    const err: any = { config: { url: '/api/p' }, response: { status: 403, data: {} } }
    await expect(resRejected(err)).rejects.toBe(err)
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('登录页遇到 401 不跳转', async () => {
    await mockLocation('/login')
    const err: any = { config: { url: '/api/login' }, response: { status: 401, data: {} } }
    await expect(resRejected(err)).rejects.toBe(err)
    expect((window.location as any).href).toBe('')
  })

  it('AI 接口超时给出特定提示', async () => {
    const err: any = { code: 'ECONNABORTED', config: { url: '/api/interview/generate' }, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: 'AI 服务响应超时，可能正在冷启动或推理中，请稍后重试' })
  })

  it('认证接口超时给出冷启动提示', async () => {
    const err: any = { code: 'ECONNABORTED', config: { url: '/api/auth/login' }, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('冷启动') })
  })

  it('通用超时给出默认提示', async () => {
    const err: any = { code: 'ECONNABORTED', config: { url: '/api/other' }, message: '' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('请求超时') })
  })

  it('HTML 内容响应提示后端未响应', async () => {
    const err: any = { config: { url: '/api/x' }, response: { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' }, data: '<html>404</html>' } }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('后端服务未响应') })
  })

  it('纯 Network Error 提示网络连接失败', async () => {
    const err: any = { config: { url: '/api/y' }, message: 'Network Error' }
    await expect(resRejected(err)).rejects.toMatchObject({ message: expect.stringContaining('网络连接失败') })
  })

  it('普通错误保持原样 reject', async () => {
    const err = new Error('业务错误')
    await expect(resRejected(err)).rejects.toThrow('业务错误')
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

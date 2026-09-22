import { describe, it, expect, beforeEach, vi } from 'vitest'

/** 用 vi.mock 顶替 axios，避免真实网络请求（探测走裸 axios） */
const getMock = vi.fn()
vi.mock('axios', () => ({ default: { get: (...args: unknown[]) => getMock(...args) } }))

import {
  ensureAwake,
  prewarmBackend,
  isBackendKnownReady,
  wakeState,
  wakeConfig,
  __resetWakeStateForTest,
} from './backendWake'

describe('utils/backendWake 冷启动唤醒器', () => {
  beforeEach(() => {
    getMock.mockReset()
    // 默认视为不可达：未显式 mock 的调用不应被误判为"探测成功"
    getMock.mockRejectedValue(new Error('Network Error'))
    __resetWakeStateForTest()
    wakeConfig.probeTimeoutMs = 10
    wakeConfig.retryIntervalMs = 1
    wakeConfig.budgetMs = 30
    wakeConfig.readyTtlMs = 30000
  })

  it('探测成功 → ready，并进入「已知就绪」窗口', async () => {
    getMock.mockResolvedValue({ status: 200, data: {} })
    await expect(ensureAwake()).resolves.toBe(true)
    expect(wakeState.status).toBe('ready')
    expect(isBackendKnownReady()).toBe(true)
    expect(wakeState.attempts).toBe(1)
  })

  it('预算内始终不可达 → failed，且不误报就绪', async () => {
    getMock.mockRejectedValue(new Error('Network Error'))
    await expect(ensureAwake()).resolves.toBe(false)
    expect(wakeState.status).toBe('failed')
    expect(isBackendKnownReady()).toBe(false)
  })

  it('并发调用共享同一次探测（单飞），不会放大冷启动压力', async () => {
    let resolveProbe: (v: unknown) => void = () => {}
    getMock.mockImplementation(
      () => new Promise((resolve) => { resolveProbe = resolve })
    )
    const p1 = ensureAwake()
    const p2 = ensureAwake()
    const p3 = ensureAwake()
    expect(getMock).toHaveBeenCalledTimes(1)
    resolveProbe({ status: 200 })
    await expect(Promise.all([p1, p2, p3])).resolves.toEqual([true, true, true])
    expect(getMock).toHaveBeenCalledTimes(1)
  })

  it('就绪 TTL 内复用结果，不重复探测', async () => {
    getMock.mockResolvedValue({ status: 200 })
    await ensureAwake()
    await ensureAwake()
    expect(getMock).toHaveBeenCalledTimes(1)
  })

  it('命中失败后再次调用会重新探测（允许用户手动重试）', async () => {
    // budgetMs=0 → 只探测一次即判定失败，便于精确验证"失败后可重试"
    wakeConfig.budgetMs = 0
    await expect(ensureAwake()).resolves.toBe(false)
    expect(wakeState.status).toBe('failed')
    getMock.mockResolvedValue({ status: 200 })
    await expect(ensureAwake()).resolves.toBe(true)
    expect(wakeState.status).toBe('ready')
  })

  it('prewarmBackend 为 fire-and-forget，失败不抛错', () => {
    wakeConfig.budgetMs = 0
    expect(() => prewarmBackend()).not.toThrow()
  })

  it('探测使用无鉴权的 /api/info，且不携带任何自定义请求头', async () => {
    getMock.mockResolvedValue({ status: 200 })
    await ensureAwake()
    const [url, opts] = getMock.mock.calls[0]
    expect(String(url)).toContain('/api/info')
    // 回归防线：跨域下自定义头会触发 OPTIONS 预检，后端白名单未含时预检 403，
    // 浏览器将拦截真实请求，导致唤醒器永远探测失败（见 2026-09-19 真机事故）。
    expect(opts?.headers ?? {}).toEqual({})
    // 缓存失效改用 URL 时间戳参数
    expect(opts?.params?._t).toBeTypeOf('number')
    // 任何 HTTP 响应都视为实例已唤醒（4xx/5xx 同样证明进程已起来）
    expect(opts?.validateStatus?.()).toBe(true)
  })

  it('后端进程返回 5xx（业务 JSON）仍判定为已唤醒（实例存活即算就绪）', async () => {
    getMock.mockResolvedValue({ status: 503, data: { code: 503, message: 'AI 服务暂时不可用' } })
    await expect(ensureAwake()).resolves.toBe(true)
    expect(wakeState.status).toBe('ready')
  })

  /**
   * 回归防线（2026-09-22）：实例未就绪时 Render 边缘节点会返回 502/503/504 的 HTML 错误页。
   * 这**不能**算就绪——否则唤醒器会立刻宣布 ready，紧接着的登录 POST 再吃一个 502，
   * 用户看到的是「秒失败」，而不是「请耐心等待启动」。
   */
  it('边缘节点 5xx（非业务 JSON）不算就绪，继续轮询', async () => {
    getMock.mockResolvedValue({ status: 502, data: '<html>Bad Gateway</html>' })
    await expect(ensureAwake()).resolves.toBe(false)
    expect(wakeState.status).toBe('failed')
    expect(wakeState.errorKind).toBe('booting')
  })

  it('连接被挂起（本地超时）归类为 booting，而非网络故障', async () => {
    getMock.mockRejectedValue(Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }))
    await expect(ensureAwake()).resolves.toBe(false)
    expect(wakeState.errorKind).toBe('booting')
  })

  it('连接被拒（无响应体且非超时）归类为 network', async () => {
    getMock.mockRejectedValue(new Error('Network Error'))
    await expect(ensureAwake()).resolves.toBe(false)
    expect(wakeState.errorKind).toBe('network')
  })

  /**
   * 「继续等待」语义：探测进行中再次调用 ensureAwake，应把预算往后延，
   * 而不是重开一轮（否则用户每点一次，已等待的几分钟就清零重来）。
   */
  it('探测进行中再次调用会延长本轮预算，不重新开始计时', async () => {
    // 单次探测挂起 200ms；预算先给 30ms（不足以等到成功）
    wakeConfig.probeTimeoutMs = 1000
    wakeConfig.retryIntervalMs = 1
    let resolveProbe: (v: unknown) => void = () => {}
    getMock.mockImplementation(
      () => new Promise((resolve) => { resolveProbe = resolve })
    )
    const first = ensureAwake(30)
    const elapsedBefore = wakeState.elapsedMs
    // 模拟用户点击「继续等待」：追加预算
    const second = ensureAwake(5000)
    expect(getMock).toHaveBeenCalledTimes(1)
    resolveProbe({ status: 200 })
    await expect(Promise.all([first, second])).resolves.toEqual([true, true])
    expect(wakeState.status).toBe('ready')
    // 起点未被重置（elapsedMs 单调累计），说明是「追加」而非「重来」
    expect(wakeState.elapsedMs).toBeGreaterThanOrEqual(elapsedBefore)
  })

  it('失败后继续等待不清零已等待时间，成功时复位', async () => {
    wakeConfig.budgetMs = 0
    await expect(ensureAwake()).resolves.toBe(false)
    const waitedAfterFail = wakeState.elapsedMs
    getMock.mockResolvedValue({ status: 200 })
    await expect(ensureAwake()).resolves.toBe(true)
    expect(wakeState.status).toBe('ready')
    expect(wakeState.elapsedMs).toBeGreaterThanOrEqual(waitedAfterFail)
  })
})

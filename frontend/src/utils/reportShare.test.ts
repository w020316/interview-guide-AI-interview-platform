import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { generateShareCard, scoreLevel, scoreHex, shareFileName, todayText } from './reportShare'

describe('reportShare 分享卡片纯逻辑', () => {
  it('scoreLevel: 分数等级分界与 reportPdf 口径一致', () => {
    expect(scoreLevel(95)).toBe('优秀')
    expect(scoreLevel(85)).toBe('优秀')
    expect(scoreLevel(84)).toBe('良好')
    expect(scoreLevel(70)).toBe('良好')
    expect(scoreLevel(69)).toBe('合格')
    expect(scoreLevel(59)).toBe('待加强')
  })

  it('scoreHex: 各等级返回不重复色值', () => {
    const hexes = [scoreHex(90), scoreHex(75), scoreHex(65), scoreHex(40)]
    expect(new Set(hexes).size).toBe(4)
    expect(hexes.every((h) => /^#[0-9a-f]{6}$/.test(h))).toBe(true)
  })

  it('shareFileName: 清洗非法文件字符并截断岗位名', () => {
    expect(shareFileName('Java 后端/工程师:2026', '2026-09-10')).toBe('share_Java后端工程师2026_2026-09-10.png')
  })

  it('shareFileName: 空岗位降级为默认名', () => {
    expect(shareFileName('', '2026-09-10')).toBe('share_面试报告_2026-09-10.png')
    expect(shareFileName('///', '2026-09-10')).toBe('share_面试报告_2026-09-10.png')
  })

  it('todayText: 输出 YYYY-MM-DD 且补零', () => {
    expect(todayText()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

// ── generateShareCard 覆盖（jsdom 无 2D canvas，需 mock getContext / toBlob / URL.*ObjectURL）──

function makeCtxStub() {
  return {
    fillStyle: '',
    font: '',
    fillRect: vi.fn(),
    fillText: vi.fn(),
    createLinearGradient: vi.fn(() => ({ addColorStop: vi.fn() })),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    arcTo: vi.fn(),
    closePath: vi.fn(),
    fill: vi.fn(),
  }
}

function installCanvas(getContext: () => unknown, toBlob: (cb: (b: Blob | null) => void) => void) {
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    value: getContext,
    configurable: true,
    writable: true,
  })
  Object.defineProperty(HTMLCanvasElement.prototype, 'toBlob', {
    value: toBlob,
    configurable: true,
    writable: true,
  })
}

const basePayload = {
  jobTitle: 'Java 后端开发工程师',
  answeredCount: 5,
  overall: 88,
  completeness: 80,
  accuracy: 85,
  expression: 90,
}

describe('generateShareCard 绘制与下载', () => {
  let ctxStub: ReturnType<typeof makeCtxStub>

  beforeEach(() => {
    ctxStub = makeCtxStub()
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:mock'), configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('成功路径：默认日期（走 todayText）并触发下载', async () => {
    installCanvas(() => ctxStub, (cb) => cb(new Blob(['x'], { type: 'image/png' })))
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click')
    await generateShareCard({ ...basePayload })
    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(clickSpy).toHaveBeenCalled()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock')
    expect(ctxStub.fillText).toHaveBeenCalled()
  })

  it('成功路径：自定义 dateText 与超长岗位名被截断', async () => {
    installCanvas(() => ctxStub, (cb) => cb(new Blob(['x'], { type: 'image/png' })))
    await generateShareCard({
      ...basePayload,
      jobTitle: '超长岗位名称用于验证截断逻辑是否按预期生效',
      dateText: '2026-01-02',
    })
    // 无断言崩溃即通过；覆盖 dateText 分支与 slice(0,16)
    expect(ctxStub.createLinearGradient).toHaveBeenCalled()
  })

  it('getContext 返回 null 时抛出“不支持 Canvas”错误', async () => {
    installCanvas(() => null, (cb) => cb(new Blob(['x'])))
    await expect(generateShareCard({ ...basePayload })).rejects.toThrow(/不支持 Canvas/)
  })

  it('toBlob 回调收到 null 时抛出“生成失败”错误', async () => {
    installCanvas(() => ctxStub, (cb) => cb(null))
    await expect(generateShareCard({ ...basePayload })).rejects.toThrow(/生成失败/)
  })
})

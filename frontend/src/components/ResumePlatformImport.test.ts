import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const { post, success } = vi.hoisted(() => ({ post: vi.fn(), success: vi.fn() }))

vi.mock('../api', () => ({
  default: { post },
  AI_TIMEOUT: 180000,
  getErrMessage: (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success, error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

import ResumePlatformImport from './ResumePlatformImport.vue'
import { HANDOFF_WINDOW_MS } from '../utils/appLaunch'
import {
  RESUME_IMPORT_PLATFORMS,
  RESUME_PICK_SOURCES,
  THIRD_PARTY_BOUNDARY_NOTE,
  THIRD_PARTY_CONSENT_TEXT,
} from '../utils/resumeIntake'

/**
 * 第三方平台导入组件测试（v1.90.0）。
 *
 * 纯决策逻辑（唤起模式、链接判定）已由 `resumeIntake.test.ts` 锁住，
 * 这里只验渲染与交互契约：
 *   1. 桌面端不给「在 App 中打开」，避免点了没反应；
 *   2. 分享链接导入必须过授权勾选，请求体带 authorized=true；
 *   3. 后端失败文案原样落到 role=alert 的内联错误里；
 *   4. 内置浏览器里点 App 入口不尝试 scheme，直接走网页版并说明原因。
 */

const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0'
const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1'
const WECHAT_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) MicroMessenger/8.0.49'

function setUa(ua: string) {
  Object.defineProperty(navigator, 'userAgent', { configurable: true, get: () => ua })
}

function mountComponent() {
  return mount(ResumePlatformImport, { props: { targetJob: 'Java 后端' } })
}

async function fillAndConsent(wrapper: ReturnType<typeof mountComponent>, url: string) {
  await wrapper.find('#pi-link-url').setValue(url)
  await wrapper.find('.pi-consent input').setValue(true)
}

beforeEach(() => {
  post.mockReset()
  success.mockReset()
  setUa(DESKTOP_UA)
})

afterEach(() => {
  setUa(DESKTOP_UA)
  vi.useRealTimers()
  document.body.innerHTML = ''
  document.body.style.overflow = ''
  vi.restoreAllMocks()
})

describe('ResumePlatformImport · 平台卡片', () => {
  it('渲染全部平台卡片，并如实标注导出方式与授权边界', () => {
    const wrapper = mountComponent()
    expect(wrapper.findAll('.pi-card')).toHaveLength(RESUME_IMPORT_PLATFORMS.length)
    expect(wrapper.text()).toContain('BOSS直聘')
    expect(wrapper.text()).toContain('微信文件')
    expect(wrapper.text()).toContain(THIRD_PARTY_CONSENT_TEXT)
    expect(wrapper.text()).toContain(THIRD_PARTY_BOUNDARY_NOTE)
  })

  it('桌面端不渲染「在 App 中打开」', () => {
    const wrapper = mountComponent()
    expect(wrapper.text()).not.toContain('在 App 中打开')
  })

  it('点击「选择 PDF 文件」抛出 pick-file（由页面打开文件选择器）', async () => {
    const wrapper = mountComponent()
    const pick = wrapper.findAll('button').find((b) => b.text().includes('选择 PDF 文件'))
    expect(pick).toBeTruthy()
    await pick!.trigger('click')
    expect(wrapper.emitted('pick-file')).toHaveLength(1)
  })

  it('网页版被拦截（window.open 返回 null）时给出可操作提示，而非宣称已打开', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    const wrapper = mountComponent()
    const openWeb = wrapper.findAll('button').find((b) => b.text().includes('打开网页版'))
    await openWeb!.trigger('click')
    expect(wrapper.find('.pi-notice').text()).toContain('未能自动打开')
  })
})

describe('ResumePlatformImport · 分享链接授权导入', () => {
  it('未勾选授权时提交按钮禁用，且不发请求', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#pi-link-url').setValue('https://example.com/resume.pdf')
    const submit = wrapper.find('.pi-submit')
    expect(submit.attributes('disabled')).toBeDefined()
    await submit.trigger('click')
    expect(post).not.toHaveBeenCalled()
  })

  it('授权后提交，请求带 authorized=true，成功后抛出 imported 并清空表单', async () => {
    post.mockResolvedValue({
      analysis: '{"overallScore":80}',
      resumeText: '简历正文',
      fileName: 'resume.pdf',
    })
    const wrapper = mountComponent()
    await fillAndConsent(wrapper, 'https://example.com/resume.pdf')
    await wrapper.find('.pi-submit').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledTimes(1)
    const [url, body, config] = post.mock.calls[0]
    expect(url).toBe('/api/resume/import-pdf-url')
    expect(body).toMatchObject({
      url: 'https://example.com/resume.pdf',
      targetJob: 'Java 后端',
      authorized: true,
    })
    expect(config).toMatchObject({ timeout: 180000 })

    expect(wrapper.emitted('imported')?.[0][0]).toMatchObject({
      resumeText: '简历正文',
      fileName: 'resume.pdf',
    })
    expect(success).toHaveBeenCalled()
    expect((wrapper.find('#pi-link-url').element as HTMLInputElement).value).toBe('')
  })

  it('后端拒绝时把原因落到内联错误（role=alert），并恢复可重试状态', async () => {
    post.mockRejectedValue(new Error('链接返回的是网页，不是 PDF 文件，请复制 PDF 直链'))
    const wrapper = mountComponent()
    await fillAndConsent(wrapper, 'https://example.com/share')
    await wrapper.find('.pi-submit').trigger('click')
    await flushPromises()

    const error = wrapper.find('.pi-error')
    expect(error.exists()).toBe(true)
    expect(error.attributes('role')).toBe('alert')
    expect(error.text()).toContain('不是 PDF 文件')
    expect(wrapper.find('.pi-progress').exists()).toBe(false)
    expect(wrapper.find('.pi-submit').attributes('disabled')).toBeUndefined()
  })

  it('非 http(s) 链接不进入请求', async () => {
    const wrapper = mountComponent()
    await fillAndConsent(wrapper, 'javascript:alert(1)')
    await wrapper.find('.pi-submit').trigger('click')
    await flushPromises()
    expect(post).not.toHaveBeenCalled()
    expect(wrapper.find('.pi-error').text()).toContain('http')
  })
})

describe('ResumePlatformImport · 移动端唤起', () => {
  it('普通手机浏览器渲染「在 App 中打开」', () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    expect(wrapper.text()).toContain('在 App 中打开')
  })

  it('微信内置浏览器：不尝试 scheme，直接打开网页版并说明宿主拦截', async () => {
    setUa(WECHAT_UA)
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window)
    const wrapper = mountComponent()
    const launch = wrapper.findAll('button').find((b) => b.text().includes('在 App 中打开'))
    await launch!.trigger('click')

    expect(open).toHaveBeenCalledWith(RESUME_IMPORT_PLATFORMS[0].webUrl, '_blank', 'noopener,noreferrer')
    expect(wrapper.find('.pi-notice').text()).toContain('内置浏览器')
    expect(wrapper.find('.pi-notice').text()).toContain('已为你打开网页版入口')
  })

  it('微信内置浏览器顶部给出「选择文件只给相册」的跳出提示', () => {
    setUa(WECHAT_UA)
    const wrapper = mountComponent()
    const tip = wrapper.find('.pi-wechat-tip')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toContain('在浏览器打开')
  })

  it('非微信环境不出现微信内置浏览器提示', () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    expect(wrapper.find('.pi-wechat-tip').exists()).toBe(false)
  })
})

describe('ResumePlatformImport · 取件向导（v1.90.1）', () => {
  function launchButton(wrapper: ReturnType<typeof mountComponent>) {
    return wrapper.findAll('button').find((b) => b.text().includes('在 App 中打开'))!
  }

  function cardLaunchButton(wrapper: ReturnType<typeof mountComponent>, key: string) {
    const index = RESUME_IMPORT_PLATFORMS.findIndex((p) => p.key === key)
    const card = wrapper.findAll('.pi-card')[index]
    return card.findAll('button').find((b) => b.text().includes('在 App 中打开'))!
  }

  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('手机端点「在 App 中打开」立即出现向导：平台步骤 + 位置选择', async () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    await launchButton(wrapper).trigger('click')

    const sheet = document.querySelector('.ri-sheet')
    expect(sheet).not.toBeNull()
    expect(sheet!.textContent).toContain('正在尝试打开「BOSS直聘」')
    expect(sheet!.querySelectorAll('.ri-step')).toHaveLength(3)
    expect(sheet!.querySelectorAll('.ri-source')).toHaveLength(RESUME_PICK_SOURCES.length)
    expect(sheet!.textContent).toContain('手机文件 · 下载')
    // v1.90.2：截图提取是主路径（不用下载保存）
    expect(sheet!.querySelector('.ri-shot-btn')!.textContent).toContain('选择截图，AI 直接提取')
    wrapper.unmount()
  })

  it('交棒后切到就绪态并提示已切回；选位置后关闭向导并请求选择文件', async () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    await launchButton(wrapper).trigger('click')

    window.dispatchEvent(new Event('blur'))
    vi.advanceTimersByTime(HANDOFF_WINDOW_MS)
    await flushPromises()

    expect(document.querySelector('.ri-title')!.textContent).toBe('从「BOSS直聘」提取简历')
    expect(document.querySelector('.ri-lead')!.textContent).toContain('切回来了')

    ;(document.querySelectorAll('.ri-source')[0] as HTMLButtonElement).click()
    await flushPromises()
    expect(wrapper.emitted('pick-file')).toHaveLength(1)
    expect(document.querySelector('.ri-sheet')).toBeNull()
    wrapper.unmount()
  })

  it('关闭向导后可从提示里的「查看取件步骤」重新打开，不再次唤起', async () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    await launchButton(wrapper).trigger('click')
    vi.advanceTimersByTime(HANDOFF_WINDOW_MS)
    await flushPromises()

    ;(document.querySelector('.ri-close') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.querySelector('.ri-sheet')).toBeNull()

    const reopen = wrapper.findAll('button').find((b) => b.text().includes('查看取件步骤'))
    expect(reopen).toBeTruthy()
    await reopen!.trigger('click')
    expect(document.querySelector('.ri-sheet')).not.toBeNull()
    expect(document.querySelector('.ri-title')!.textContent).toBe('从「BOSS直聘」提取简历')
    wrapper.unmount()
  })

  it('没交棒时给出兜底；向导主按钮「选择截图」转发 pick-image', async () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    await launchButton(wrapper).trigger('click')

    vi.advanceTimersByTime(HANDOFF_WINDOW_MS)
    await flushPromises()
    expect(document.querySelector('.ri-lead')!.textContent).toContain('没有跳转成功也不影响')

    ;(document.querySelector('.ri-shot-btn') as HTMLButtonElement).click()
    await flushPromises()
    expect(wrapper.emitted('pick-image')).toHaveLength(1)
    expect(document.querySelector('.ri-sheet')).toBeNull()
    wrapper.unmount()
  })

  it('微信平台（iOS）：主按钮是「选择聊天里的 PDF」，步骤用 iOS 菜单名', async () => {
    setUa(MOBILE_UA)
    const wrapper = mountComponent()
    await cardLaunchButton(wrapper, 'wechat').trigger('click')

    const sheet = document.querySelector('.ri-sheet')!
    expect(sheet.textContent).toContain('微信聊天里的 PDF')
    expect(sheet.querySelector('.ri-chat-btn')!.textContent).toContain('选择聊天里的 PDF')
    expect(sheet.querySelector('.ri-step:nth-child(2)')!.textContent).toContain('存储到「文件」')
    expect(sheet.querySelector('.ri-shot')!.classList.contains('is-secondary')).toBe(true)
    wrapper.unmount()
  })

  it('微信内置浏览器点微信卡片：不跳外站，留在本页给截图与跳出提示', async () => {
    setUa(WECHAT_UA)
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const wrapper = mountComponent()
    await cardLaunchButton(wrapper, 'wechat').trigger('click')

    expect(open).not.toHaveBeenCalled()
    expect(wrapper.find('.pi-notice').text()).toContain('在浏览器打开')
    const sheet = document.querySelector('.ri-sheet')
    expect(sheet).not.toBeNull()
    expect(document.querySelector('.ri-title')!.textContent).toBe('从「微信文件」提取简历')
    expect(sheet!.querySelector('.ri-chat-btn')).not.toBeNull()
    wrapper.unmount()
  })
})

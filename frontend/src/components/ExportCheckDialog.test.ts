import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ExportCheckDialog from './ExportCheckDialog.vue'
import api from '../api'
import { PDF_MAX_BYTES } from '../utils/exportCheck'

vi.mock('../api', () => ({
  default: { post: vi.fn() },
  // 与真实实现同语义：优先 Error.message，其次 fallback
  getErrMessage: (e: unknown, fallback: string) =>
    e instanceof Error && e.message ? e.message : fallback,
}))

const post = vi.mocked(api.post)

/** 与后端 ResumeExportCheckService 输出同形 */
const OK_REPORT = {
  verdict: 'warn',
  summary: '有 1 项需要留意：联系方式里没有找到电话或邮箱',
  pageCount: 2,
  charCount: 1234,
  checks: [
    { id: 'pdf-open', label: '文件可打开', status: 'pass', detail: 'PDF 结构完整' },
    { id: 'pages', label: '页数', status: 'pass', detail: '共 2 页' },
    { id: 'text-layer', label: '文字层', status: 'pass', detail: '可提取 1234 个字符' },
    { id: 'contact', label: '联系方式', status: 'warn', detail: '没有找到电话或邮箱' },
    { id: 'sections', label: '关键板块', status: 'pass', detail: '识别到：教育背景、项目经历' },
    { id: 'timeline', label: '时间信息', status: 'pass', detail: '识别到 4 处年份' },
    { id: 'glyphs', label: '特殊字符', status: 'pass', detail: '没有可疑字符' },
  ],
}

/** el-dialog 是 Element Plus 全局组件，单测里用最小桩：visible 时才渲染插槽 */
function mountDialog(visible = true): VueWrapper {
  return mount(ExportCheckDialog, {
    props: { visible },
    global: {
      stubs: {
        'el-dialog': {
          props: ['modelValue'],
          template: '<div v-if="modelValue" class="dialog-stub"><slot /><slot name="footer" /></div>',
        },
      },
    },
  })
}

async function pick(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

function pdf(name = 'resume.pdf'): File {
  return new File([new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d])], name, {
    type: 'application/pdf',
  })
}

afterEach(() => {
  post.mockReset()
})

describe('ExportCheckDialog —— 导出体检', () => {
  it('未打开时不渲染内容', () => {
    const wrapper = mountDialog(false)
    expect(wrapper.find('.dialog-stub').exists()).toBe(false)
  })

  it('引导文案写清「先导出 PDF 再上传」，避免用户拿 docx / 截图来体检', () => {
    const text = mountDialog().text()
    expect(text).toContain('导出 PDF')
    expect(text).toContain('另存为 PDF')
    expect(text).toContain('刚刚保存的那份文件')
    expect(text).toContain('不调用 AI')
  })

  it('非 PDF：本地拦截、不发请求、给出可操作提示', async () => {
    const wrapper = mountDialog()
    await pick(wrapper, new File(['x'], 'resume.docx'))

    expect(post).not.toHaveBeenCalled()
    expect(wrapper.find('.error-block').text()).toContain('只支持 PDF')
    expect(wrapper.find('.verdict').exists()).toBe(false)
  })

  it('超过 10MB：本地拦截，不消耗服务端配额', async () => {
    const big = pdf()
    Object.defineProperty(big, 'size', { value: PDF_MAX_BYTES + 1 })
    const wrapper = mountDialog()
    await pick(wrapper, big)

    expect(post).not.toHaveBeenCalled()
    expect(wrapper.find('.error-block').text()).toBe('文件大小不能超过 10MB')
  })

  it('合法 PDF：POST 到 /api/resume/export-check 并渲染报告', async () => {
    post.mockResolvedValue(OK_REPORT)
    const wrapper = mountDialog()
    const file = pdf()
    await pick(wrapper, file)

    expect(post).toHaveBeenCalledTimes(1)
    const [url, body] = post.mock.calls[0]
    expect(url).toBe('/api/resume/export-check')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)

    expect(wrapper.find('.verdict').classes()).toContain('is-warn')
    expect(wrapper.find('.verdict-title').text()).toBe('有提醒')
    expect(wrapper.find('.verdict-summary').text()).toContain('联系方式')
    expect(wrapper.findAll('.stat-value').map((w) => w.text())).toEqual(['2', '1,234 字'])
    expect(wrapper.findAll('.check')).toHaveLength(7)
    expect(wrapper.findAll('.check svg')).toHaveLength(7)
  })

  it('每项检查都带状态标签（通过 / 留意 / 未通过）', async () => {
    post.mockResolvedValue(OK_REPORT)
    const wrapper = mountDialog()
    await pick(wrapper, pdf())

    const statuses = wrapper.findAll('.check-status').map((s) => s.text())
    expect(statuses).toEqual(['通过', '通过', '通过', '留意', '通过', '通过', '通过'])
  })

  it('页数 / 字数缺失显示「—」而不是 0', async () => {
    post.mockResolvedValue({
      verdict: 'fail',
      checks: [{ id: 'pdf-open', label: '文件可打开', status: 'fail', detail: 'PDF 已加密' }],
    })
    const wrapper = mountDialog()
    await pick(wrapper, pdf())

    const stats = wrapper.findAll('.stat-value').map((w) => w.text())
    expect(stats[0]).toBe('—')
    expect(stats[1]).toBe('— 字')
  })

  it('接口返回形状异常：提示重试，不渲染半张报告', async () => {
    post.mockResolvedValue({ checks: [] })
    const wrapper = mountDialog()
    await pick(wrapper, pdf())

    expect(wrapper.find('.verdict').exists()).toBe(false)
    expect(wrapper.find('.error-block').text()).toContain('没有拿到有效的体检结果')
  })

  it('接口报错（如限流）：展示后端文案', async () => {
    post.mockRejectedValue(new Error('体检太频繁了，请一分钟后再试'))
    const wrapper = mountDialog()
    await pick(wrapper, pdf())

    expect(wrapper.find('.error-block').text()).toBe('体检太频繁了，请一分钟后再试')
    expect(wrapper.find('.verdict').exists()).toBe(false)
  })

  it('重新打开时清空上一轮结果（避免误以为是这次文件的结果）', async () => {
    post.mockResolvedValue(OK_REPORT)
    const wrapper = mountDialog()
    await pick(wrapper, pdf())
    expect(wrapper.find('.verdict').exists()).toBe(true)

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })

    expect(wrapper.find('.verdict').exists()).toBe(false)
    expect(wrapper.text()).toContain('选择要体检的 PDF')
  })

  it('上传中禁用选择入口（同一时间只处理一份文件）', async () => {
    let release: (v: unknown) => void = () => {}
    post.mockImplementation(() => new Promise((resolve) => (release = resolve)))
    const wrapper = mountDialog()
    await pick(wrapper, pdf())

    expect(wrapper.find('.pick-zone').attributes('disabled')).toBeDefined()
    release(OK_REPORT)
    await flushPromises()
    expect(wrapper.find('.pick-zone').attributes('disabled')).toBeUndefined()
  })
})

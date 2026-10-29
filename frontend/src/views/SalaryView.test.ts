import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
}))
vi.mock('../api', () => ({
  default: { post: vi.fn() },
  getErrMessage: (e: unknown, fallback: string) =>
    e instanceof Error && e.message ? e.message : fallback,
}))

import { ElMessage } from 'element-plus'
import api from '../api'
import SalaryView from './SalaryView.vue'

const post = vi.mocked(api.post)
const warning = vi.mocked(ElMessage.warning)
const errorMsg = vi.mocked(ElMessage.error)

/** 与后端 SalaryCoachService 输出同形 */
const SERVER_REPORT = {
  headline: '先把薪资结构问清楚，再报区间，不要报到单点。',
  analysis: [{ point: '月薪 22k 没有提薪数', basis: '来自你填写的 offer 月薪' }],
  scripts: [{ scene: 'HR 问期望薪资时', say: '我希望月薪 25k，按 14 薪算。' }],
  qa: [{ q: '这就是上限了', a: '理解，那能否在签字费或调薪周期上再看一下？' }],
  warnings: ['只谈月薪不谈薪数'],
  actions: ['把薪数写进书面 offer'],
  numbers: {
    raiseAmount: 4000,
    raisePct: 22.2,
    offerAnnual: 330000,
    expectedGapMonthly: -3000,
    expectedGapPct: -12,
  },
}

function mountView(): VueWrapper {
  return mount(SalaryView)
}

function genButton(w: VueWrapper) {
  const btn = w.findAll('button').find((b) => b.text().includes('生成谈薪建议'))
  if (!btn) throw new Error('找不到「生成谈薪建议」按钮')
  return btn
}

beforeEach(() => {
  post.mockReset()
  warning.mockReset()
  errorMsg.mockReset()
})

describe('SalaryView —— 谈薪助手', () => {
  it('默认「被问期望薪资」场景，三个场景 Tab 都在', () => {
    const w = mountView()
    const tabs = w.findAll('.mode-tab')
    expect(tabs).toHaveLength(3)
    expect(tabs[0].text()).toContain('被问期望薪资')
    expect(tabs[1].text()).toContain('评估 offer')
    expect(tabs[2].text()).toContain('正在还价')
    expect(tabs[0].classes()).toContain('is-active')
  })

  it('页面明说不编造行情，也不替用户做决定', () => {
    const text = mountView().text()
    expect(text).toContain('不编造任何市场行情')
    expect(text).toContain('不会替你决定接或不接')
  })

  it('目标岗位为空时生成按钮禁用', () => {
    const w = mountView()
    expect(genButton(w).attributes('disabled')).toBeDefined()
  })

  it('本地校验拦下非数字，不发起请求、不消耗配额', async () => {
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await w.find('#salary-current').setValue('两万')
    await genButton(w).trigger('click')
    await flushPromises()

    expect(post).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('当前月薪请填写数字')
  })

  it('生成成功：提交数字并渲染结论、金额、风险、话术与动作', async () => {
    post.mockResolvedValue(SERVER_REPORT)
    const w = mountView()
    await w.find('#salary-job').setValue(' Java 后端 ')
    await w.find('#salary-offer').setValue('22000')
    await w.find('#salary-months').setValue('15')
    await w.find('#salary-expected').setValue('25000')
    await genButton(w).trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledTimes(1)
    const [url, payload] = post.mock.calls[0]
    expect(url).toBe('/api/career/salary')
    expect(payload).toMatchObject({
      mode: 'EXPECTATION',
      targetJob: 'Java 后端',
      offerMonthly: 22000,
      offerMonths: 15,
      expectedMonthly: 25000,
      currentMonthly: null,
    })

    expect(w.find('.headline-text').text()).toContain('先把薪资结构问清楚')
    const values = w.findAll('.num-value').map((n) => n.text())
    expect(values).toEqual(['+4,000 元', '330,000 元', '-3,000 元'])
    expect(w.text()).toContain('相对当前月薪 +22.2%')
    expect(w.text()).toContain('相对期望月薪 -12%')
    expect(w.find('.warn-title').text()).toContain('风险')
    expect(w.text()).toContain('只谈月薪不谈薪数')
    expect(w.text()).toContain('我希望月薪 25k')
    expect(w.text()).toContain('这就是上限了')
    expect(w.text()).toContain('把薪数写进书面 offer')
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('金额全部算不出：显示「填数字才能算」的说明，绝不当成 0', async () => {
    post.mockResolvedValue({
      headline: '先问清薪数再谈月薪',
      analysis: [],
      scripts: [],
      qa: [],
      warnings: [],
      actions: ['今天就问 HR 薪数'],
      numbers: {
        raiseAmount: null,
        raisePct: null,
        offerAnnual: null,
        expectedGapMonthly: null,
        expectedGapPct: null,
      },
    })
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await genButton(w).trigger('click')
    await flushPromises()

    expect(w.findAll('.num-card')).toHaveLength(0)
    expect(w.find('.numbers-hint').text()).toContain('缺失的数字不会被当成 0')
    expect(w.text()).not.toContain('0 元')
  })

  it('负数涨幅用 is-down（offer 低于当前月薪是真实结果，不是错误）', async () => {
    post.mockResolvedValue(SERVER_REPORT)
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await genButton(w).trigger('click')
    await flushPromises()

    const values = w.findAll('.num-value')
    expect(values[0].classes()).toContain('is-up')
    expect(values[2].classes()).toContain('is-down')
  })

  it('切换场景会清空上一份建议（不同场景不可混用）', async () => {
    post.mockResolvedValue(SERVER_REPORT)
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await genButton(w).trigger('click')
    await flushPromises()
    expect(w.find('.headline').exists()).toBe(true)

    await w.findAll('.mode-tab')[2].trigger('click')
    expect(w.find('.headline').exists()).toBe(false)
    expect(w.findAll('.mode-tab')[2].classes()).toContain('is-active')
  })

  it('接口没有返回任何内容：提示重试，不渲染空报告', async () => {
    post.mockResolvedValue({ headline: '', analysis: [], scripts: [], qa: [], warnings: [], actions: [] })
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await genButton(w).trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith(expect.stringContaining('没有生成有效建议'))
    expect(w.find('.headline').exists()).toBe(false)
  })

  it('接口报错（如限流）：透传后端文案', async () => {
    post.mockRejectedValue(new Error('谈薪建议生成太频繁了，请稍后再试'))
    const w = mountView()
    await w.find('#salary-job').setValue('Java 后端')
    await genButton(w).trigger('click')
    await flushPromises()

    expect(errorMsg).toHaveBeenCalledWith('谈薪建议生成太频繁了，请稍后再试')
    expect(w.find('.headline').exists()).toBe(false)
    expect(genButton(w).attributes('disabled')).toBeUndefined()
  })
})

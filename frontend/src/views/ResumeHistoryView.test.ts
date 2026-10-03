import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 组件级回归测试：锁定 P0「简历解析失败被伪装成 0 分」在列表与详情两处的表现。
 * 复现原缺陷的输入形状（线上真实兜底串 overallScore:0 + 兜底 improvements）。
 */

const { get, push } = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../api', () => ({
  default: { get },
  getErrMessage: (_e: unknown, fallback: string) => fallback,
}))

import ResumeHistoryView from './ResumeHistoryView.vue'

/** 线上真实兜底串（id=17 形状）：overallScore:0 + 「无法解析」improvements */
const FAILED_ANALYSIS = JSON.stringify({
  strengths: [],
  dimensions: [],
  improvements: ['AI 返回内容无法解析，请稍后重试'],
  overallScore: 0,
})

/** 正常分析结果（id=18 形状） */
const NORMAL_ANALYSIS = JSON.stringify({
  strengths: ['项目经验扎实'],
  dimensions: [
    { name: '完整性', score: 80, suggestion: '补充量化指标' },
    { name: '准确性', score: 76, suggestion: '表述更精确' },
  ],
  improvements: ['增加量化结果'],
  overallScore: 78,
})

function makeResume(id: number, overallScore: number | null, analysisResult: string) {
  return {
    id,
    userId: 'u1',
    content: 'Java 后端简历正文',
    targetJob: 'Java 后端',
    overallScore,
    analysisResult,
    createdAt: '2026-10-03T15:30:00',
  }
}

function mockApi(list: ReturnType<typeof makeResume>[]) {
  get.mockImplementation((url: string) => {
    if (url === '/api/resume/history') return Promise.resolve(list)
    const id = Number(url.split('/').pop())
    return Promise.resolve(list.find((r) => r.id === id) ?? list[0])
  })
}

describe('ResumeHistoryView 失败态渲染', () => {
  beforeEach(() => {
    get.mockReset()
    push.mockReset()
  })

  it('失败记录：列表卡片显示「解析失败」而非「0 分」，且不套低分红底', async () => {
    mockApi([makeResume(17, 0, FAILED_ANALYSIS)])
    const wrapper = mount(ResumeHistoryView)
    await flushPromises()

    expect(wrapper.find('.score-failed').text()).toContain('解析失败')
    expect(wrapper.find('.score-num').exists()).toBe(false)
    const style = wrapper.find('.card-score').attributes('style') || ''
    expect(style).toContain('var(--c-bg-alt)')
    expect(style).not.toContain('ef4444')
  })

  it('正常记录：列表卡片显示真实分数', async () => {
    mockApi([makeResume(18, 78, NORMAL_ANALYSIS)])
    const wrapper = mount(ResumeHistoryView)
    await flushPromises()

    expect(wrapper.find('.score-failed').exists()).toBe(false)
    expect(wrapper.find('.score-num').text()).toBe('78')
  })

  it('失败记录详情：渲染明确错误态，且不渲染评分圆环区', async () => {
    mockApi([makeResume(17, 0, FAILED_ANALYSIS)])
    const wrapper = mount(ResumeHistoryView)
    await flushPromises()

    await wrapper.find('.resume-card').trigger('click')
    await flushPromises()

    expect(wrapper.find('.analysis-failed').exists()).toBe(true)
    expect(wrapper.find('.analysis-failed').text()).toContain('未能生成有效结果')
    expect(wrapper.find('.detail-score-row').exists()).toBe(false)
  })

  it('正常记录详情：渲染评分圆环与维度明细，不出现错误态', async () => {
    mockApi([makeResume(18, 78, NORMAL_ANALYSIS)])
    const wrapper = mount(ResumeHistoryView)
    await flushPromises()

    await wrapper.find('.resume-card').trigger('click')
    await flushPromises()

    expect(wrapper.find('.analysis-failed').exists()).toBe(false)
    expect(wrapper.find('.detail-score-row').exists()).toBe(true)
    expect(wrapper.find('.dim-list').exists()).toBe(true)
  })

  it('真实 0 分（有维度明细）：详情仍渲染评分区，不误判为失败', async () => {
    const realZero = JSON.stringify({
      strengths: ['有基础'],
      dimensions: [{ name: '完整性', score: 0, suggestion: '内容缺失' }],
      improvements: ['系统性重写'],
      overallScore: 0,
    })
    mockApi([makeResume(19, 0, realZero)])
    const wrapper = mount(ResumeHistoryView)
    await flushPromises()

    expect(wrapper.find('.score-failed').exists()).toBe(false)

    await wrapper.find('.resume-card').trigger('click')
    await flushPromises()

    expect(wrapper.find('.analysis-failed').exists()).toBe(false)
    expect(wrapper.find('.detail-score-row').exists()).toBe(true)
  })
})

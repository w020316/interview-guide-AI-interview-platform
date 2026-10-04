import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ReportPanel from './ReportPanel.vue'
import type { ReportEvalInput } from '../utils/reportView'

/**
 * RK1 断言级防护（组件层）：旧会话 eval_detail 为 null ⇒
 *   [data-report-degraded="true"] 存在 且 .report-dim 数量 === 0（不是「值为 0」）。
 */
describe('ReportPanel · 降级渲染', () => {
  const degradedEvals: ReportEvalInput[] = [
    { question: 'Q1', category: 'Java', overallScore: 70, evalDetail: null },
    { question: 'Q2', category: 'Java', overallScore: 80, evalDetail: null },
  ]
  const fullEvals: ReportEvalInput[] = [
    {
      question: 'Q1',
      category: 'Java',
      overallScore: 76,
      evalDetail: { completeness: 72, accuracy: 65, expression: 60 },
    },
  ]

  it('旧会话（detail 全 null）→ data-report-degraded=true 且 .report-dim 数 === 0', () => {
    const wrapper = mount(ReportPanel, { props: { evals: degradedEvals, scopeText: 'x' } })

    expect(wrapper.find('.report-modal').attributes('data-report-degraded')).toBe('true')
    expect(wrapper.findAll('.report-dim')).toHaveLength(0)
    // 有明确的降级说明，而不是「0 分」维度条
    expect(wrapper.find('[data-report-degraded-note]').exists()).toBe(true)
  })

  it('有完整 detail → data-report-degraded=false 且渲染四条维度条', () => {
    const wrapper = mount(ReportPanel, { props: { evals: fullEvals, scopeText: 'x' } })

    expect(wrapper.find('.report-modal').attributes('data-report-degraded')).toBe('false')
    expect(wrapper.findAll('.report-dim')).toHaveLength(4)
    expect(wrapper.find('[data-report-degraded-note]').exists()).toBe(false)
  })

  it('降级时「分享卡片 / 导出 PDF」入口仍可见（导出模块已降级感知，不补 0）', () => {
    const wrapper = mount(ReportPanel, { props: { evals: degradedEvals } })
    const text = wrapper.text()
    expect(text).toContain('分享卡片')
    expect(text).toContain('导出 PDF')
    expect(text).toContain('查看历史记录')
  })

  it('非降级时展示「分享卡片 / 导出 PDF」', () => {
    const wrapper = mount(ReportPanel, { props: { evals: fullEvals } })
    const text = wrapper.text()
    expect(text).toContain('分享卡片')
    expect(text).toContain('导出 PDF')
  })

  it('降级时点击导出按钮仍 emit export-pdf（不再因降级拦截入口）', async () => {
    const wrapper = mount(ReportPanel, { props: { evals: degradedEvals } })
    const exportBtn = wrapper.findAll('button').find((b) => b.text().includes('导出 PDF'))!
    await exportBtn.trigger('click')
    expect(wrapper.emitted('export-pdf')).toBeTruthy()
  })

  it('按钮 emit 正确事件', async () => {
    const wrapper = mount(ReportPanel, {
      props: { evals: fullEvals, compare: { status: 'steady', label: '持平', hint: 'h', nextTarget: 80 }, compareLoaded: true },
    })
    await wrapper.find('.report-close').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
    // 导出按钮存在时点击应 emit export-pdf
    const buttons = wrapper.findAll('button')
    const exportBtn = buttons.find((b) => b.text().includes('导出 PDF'))!
    await exportBtn.trigger('click')
    expect(wrapper.emitted('export-pdf')).toBeTruthy()
  })
})

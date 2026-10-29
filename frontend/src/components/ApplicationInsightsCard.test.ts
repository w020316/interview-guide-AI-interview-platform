import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ApplicationInsightsCard from './ApplicationInsightsCard.vue'
import { normalizeInsights, type ApplicationInsights } from '../utils/applicationInsights'

/**
 * 漏斗洞察卡片测试（v1.86.0）
 *
 * 数据整形已由 `applicationInsights.test.ts` 锁住，这里只验**渲染决策**：
 * 「无数据 ≠ 0%」在界面上真的成立、小样本不给出流失结论、静默数照实展示。
 */

const FULL: ApplicationInsights = normalizeInsights({
  funnelStages: [
    { id: 'submitted', label: '已投出', count: 6, conversionFromPrev: null, conversionFromSubmitted: null },
    { id: 'viewed', label: '被查看', count: 5, conversionFromPrev: 0.833, conversionFromSubmitted: 0.833 },
    { id: 'replied', label: '有回复', count: 3, conversionFromPrev: 0.6, conversionFromSubmitted: 0.5 },
    { id: 'interview', label: '进面试', count: 2, conversionFromPrev: 0.667, conversionFromSubmitted: 0.333 },
    { id: 'offer', label: '拿 Offer', count: 1, conversionFromPrev: 0.5, conversionFromSubmitted: 0.167 },
  ],
  response: { sampleSize: 3, avgDays: 5, medianDays: 4 },
  silentCount: 2,
})

function mountWith(insights: ApplicationInsights) {
  return mount(ApplicationInsightsCard, { props: { insights } })
}

describe('ApplicationInsightsCard', () => {
  it('渲染五段漏斗，人数与逐段转化率正确', () => {
    const wrapper = mountWith(FULL)
    const stages = wrapper.findAll('.ins-stage')
    expect(stages).toHaveLength(5)
    expect(stages[0].text()).toContain('已投出')
    expect(stages[0].text()).toContain('6')
    expect(stages[1].find('.ins-stage-rate').text()).toBe('83%')
    expect(stages[4].find('.ins-stage-rate').text()).toBe('50%')
  })

  it('转化率为 null 时显示占位符，不显示 0%', () => {
    const wrapper = mountWith(
      normalizeInsights({
        funnelStages: [
          { id: 'submitted', label: '已投出', count: 0, conversionFromPrev: null, conversionFromSubmitted: null },
          { id: 'viewed', label: '被查看', count: 0, conversionFromPrev: null, conversionFromSubmitted: null },
        ],
      }),
    )
    const rates = wrapper.findAll('.ins-stage-rate')
    expect(rates[0].text()).toBe('—')
    expect(rates[1].text()).toBe('—')
    expect(wrapper.text()).not.toContain('0%')
  })

  it('平均回音时长与样本量一起展示', () => {
    const wrapper = mountWith(FULL)
    const metrics = wrapper.findAll('.ins-metric')
    expect(metrics[1].text()).toContain('5 天')
    expect(metrics[1].text()).toContain('中位数 4 天')
    expect(metrics[1].text()).toContain('样本 3 条')
  })

  it('没有样本时显示占位符 + 说明原因（不回填，也不显示 0 天）', () => {
    const wrapper = mountWith(
      normalizeInsights({
        funnelStages: [{ id: 'submitted', label: '已投出', count: 1, conversionFromPrev: null, conversionFromSubmitted: null }],
        response: { sampleSize: 0, avgDays: null, medianDays: null },
      }),
    )
    expect(wrapper.findAll('.ins-metric')[1].text()).toContain('—')
    expect(wrapper.text()).toContain('首次回音时间从本版本起开始记录')
    expect(wrapper.text()).not.toContain('0 天')
  })

  it('静默投递条数照实展示', () => {
    const wrapper = mountWith(FULL)
    expect(wrapper.findAll('.ins-metric')[2].text()).toContain('2 条')
  })

  it('投出 ≥3 条时才给出「流失最多的一步」', () => {
    const withDrop = mountWith(FULL)
    const drop = withDrop.find('.ins-drop')
    expect(drop.exists()).toBe(true)
    expect(drop.text()).toContain('被查看')
    expect(drop.text()).toContain('有回复')
    expect(drop.text()).toContain('少 2 条')
  })

  it('小样本（<3 条投出）不给出流失结论（避免把噪声当趋势）', () => {
    const wrapper = mountWith(
      normalizeInsights({
        funnelStages: [
          { id: 'submitted', label: '已投出', count: 2, conversionFromPrev: null, conversionFromSubmitted: null },
          { id: 'replied', label: '有回复', count: 0, conversionFromPrev: 0, conversionFromSubmitted: 0 },
        ],
      }),
    )
    expect(wrapper.find('.ins-drop').exists()).toBe(false)
  })

  it('条形宽度以「已投出」为基准（首段满格，末段按比例）', () => {
    const wrapper = mountWith(FULL)
    const fills = wrapper.findAll('.ins-bar-fill')
    expect(fills[0].attributes('style')).toContain('width: 100%')
    expect(fills[4].attributes('style')).toContain('width: 17%')   // 1/6
  })

  it('已投出为 0 时条形宽度为 0（没有数据就没有长度）', () => {
    const wrapper = mountWith(
      normalizeInsights({
        funnelStages: [{ id: 'submitted', label: '已投出', count: 0, conversionFromPrev: null, conversionFromSubmitted: null }],
      }),
    )
    expect(wrapper.find('.ins-bar-fill').attributes('style')).toContain('width: 0%')
  })
})
